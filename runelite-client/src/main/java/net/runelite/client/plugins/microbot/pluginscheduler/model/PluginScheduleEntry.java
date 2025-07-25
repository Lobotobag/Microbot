package net.runelite.client.plugins.microbot.pluginscheduler.model;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.DayOfWeek;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;
import java.util.function.Supplier;

import org.lwjgl.opencl.CL;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.config.ConfigDescriptor;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.pluginscheduler.api.SchedulablePlugin;
import net.runelite.client.plugins.microbot.pluginscheduler.condition.Condition;
import net.runelite.client.plugins.microbot.pluginscheduler.condition.ConditionManager;
import net.runelite.client.plugins.microbot.pluginscheduler.condition.logical.LogicalCondition;
import net.runelite.client.plugins.microbot.pluginscheduler.condition.logical.OrCondition;
import net.runelite.client.plugins.microbot.pluginscheduler.condition.logical.enums.UpdateOption;
import net.runelite.client.plugins.microbot.pluginscheduler.condition.time.DayOfWeekCondition;
import net.runelite.client.plugins.microbot.pluginscheduler.condition.time.IntervalCondition;
import net.runelite.client.plugins.microbot.pluginscheduler.condition.time.SingleTriggerTimeCondition;
import net.runelite.client.plugins.microbot.pluginscheduler.condition.time.TimeCondition;
import net.runelite.client.plugins.microbot.pluginscheduler.condition.time.TimeWindowCondition;
import net.runelite.client.plugins.microbot.pluginscheduler.condition.time.enums.RepeatCycle;
import net.runelite.client.plugins.microbot.pluginscheduler.config.ScheduleEntryConfigManager;
import net.runelite.client.plugins.microbot.pluginscheduler.event.PluginScheduleEntrySoftStopEvent;
import net.runelite.client.plugins.microbot.pluginscheduler.serialization.ScheduledSerializer;

@Data
@AllArgsConstructor
@Getter
@Slf4j
/**
 * Represents a scheduled plugin entry in the plugin scheduler system.
 * <p>
 * This class manages the scheduling, starting, stopping, and condition management
 * for a plugin. It handles both start and stop conditions through {@link ConditionManager}
 * instances and provides comprehensive state tracking for the plugin's execution.
 * <p>
 * PluginScheduleEntry serves as the core model connecting the UI components in the
 * scheduler system with the actual plugin execution logic. It maintains information about:
 * <ul>
 *   <li>When a plugin should start (start conditions)</li>
 *   <li>When a plugin should stop (stop conditions)</li>
 *   <li>Current execution state (running, stopped, enabled/disabled)</li>
 *   <li>Execution statistics (run count, duration, etc.)</li>
 *   <li>Plugin configuration and watchdog management</li>
 * </ul>
 */
public class PluginScheduleEntry implements AutoCloseable {
    // Static formatter for time display
    public static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm");
    public static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"); 
    // Remove the duplicate executor and use the shared one from ConditionManager
    
    // Store the scheduled futures so they can be cancelled later
    private transient ScheduledFuture<?> startConditionWatchdogFuture;
    private transient ScheduledFuture<?> stopConditionWatchdogFuture;
    private transient Plugin plugin;
    private String name;    
    private boolean enabled;
    private boolean allowContinue = true; // Whether to continue running the plugin after a interruption -> stopReasonType = StopReason.Interrupted
    private boolean hasStarted = false; // Flag to indicate if the plugin has started
    @Setter
    private boolean needsStopCondition = false; // Flag to indicate if a time-based stop condition is needed    
    private transient ScheduleEntryConfigManager scheduleEntryConfigManager; 

    // New fields for tracking stop reason
    private String lastStopReason;
    @Getter
    private boolean lastRunSuccessful;
    private boolean onLastStopUserConditionsSatisfied = false; // Flag to indicate if the last stop was due to satisfied conditions
    private boolean onLastStopPluginConditionsSatisfied = false; // Flag to indicate if the last stop was due to satisfied conditions
    private StopReason lastStopReasonType = StopReason.NONE;
    private Duration lastRunDuration = Duration.ZERO; // Duration of the last run
    private ZonedDateTime lastRunStartTime; // When the plugin started running
    private ZonedDateTime lastRunEndTime; // When the plugin finished running
    
    /**
    * Enumeration of reasons why a plugin might stop
    */
    public enum StopReason {
        NONE("None"),
        MANUAL_STOP("Manually Stopped"),
        PLUGIN_FINISHED("Plugin Finished"),
        ERROR("Error"),
        SCHEDULED_STOP("Scheduled Stop"),
        INTERRUPTED("Interrupted"),
        HARD_STOP("Hard Stop"),
        CLIENT_SHUTDOWN("Client Shutdown");
        
        private final String description;
        
        StopReason(String description) {
            this.description = description;
        }
        
        public String getDescription() {
            return description;
        }
        
        @Override
        public String toString() {
            return description;
        }
    }
 
     

    private String cleanName;
    final private ConditionManager stopConditionManager;
    final private ConditionManager startConditionManager;    
    private transient boolean stopInitiated = false;    

    private boolean allowRandomScheduling = true; // Whether this plugin can be randomly scheduled
    private int runCount = 0; // Track how many times this plugin has been run
    
    // Watchdog configuration
    private boolean autoStartWatchdogs = true;  // Whether to auto-start watchdogs on creation
    private boolean watchdogsEnabled = true;    // Whether watchdogs are allowed to run

    private ZonedDateTime stopInitiatedTime; // When the first stop was attempted
    private ZonedDateTime lastStopAttemptTime; // When the last stop attempt was made
    private Duration softStopRetryInterval = Duration.ofSeconds(30); // Default 30 seconds between retries
    private Duration hardStopTimeout = Duration.ofMinutes(4); // Default 4 Minutes before hard stop

    
    private transient Thread stopMonitorThread;
    private transient volatile boolean isMonitoringStop = false;

       
    private int priority = 0; // Higher numbers = higher priority
    private boolean isDefault = false; // Flag to indicate if this is a default plugin        

    /**
     * Functional interface for handling successful plugin stop events
     */
    @FunctionalInterface
    public interface StopCompletionCallback {
        /**
         * Called when a plugin has successfully completed its stop operation
         * @param entry The PluginScheduleEntry that has stopped
         * @param wasSuccessful Whether the plugin run was successful
         */
        void onStopCompleted(PluginScheduleEntry entry, boolean wasSuccessful);
    }
    
    /**
     * Callback that will be invoked when this plugin successfully stops
     */
    private transient StopCompletionCallback stopCompletionCallback;
    
    /**
     * Sets the callback to be invoked when this plugin successfully stops
     * @param callback The callback to invoke
     * @return This PluginScheduleEntry for method chaining
     */
    public PluginScheduleEntry setStopCompletionCallback(StopCompletionCallback callback) {
        this.stopCompletionCallback = callback;
        return this;
    }
    
  
    /**
     * Sets the serialized ConfigDescriptor for this schedule entry
     * This is used during deserialization
     * 
     * @param serializedConfigDescriptor The serialized ConfigDescriptor as a JsonObject
     */
    public void setSerializedConfigDescriptor(ConfigDescriptor serializedConfigDescriptor) {        
        // If we already have a scheduleEntryConfigManager, update it with the new config
        if (this.scheduleEntryConfigManager != null) {
            this.scheduleEntryConfigManager.setConfigScheduleEntryDescriptor(serializedConfigDescriptor);
        }
    }
    
    /**
     * Gets the serialized ConfigDescriptor for this schedule entry
     * 
     * @return The serialized ConfigDescriptor as a JsonObject, or null if not set
     */
    public ConfigDescriptor getConfigScheduleEntryDescriptor() {
        // If we have a scheduleEntryConfigManager, get the serialized config from it
        if (this.scheduleEntryConfigManager != null) {
            return this.scheduleEntryConfigManager.getConfigScheduleEntryDescriptor();
        }        
        return null;
    }
    public PluginScheduleEntry(String pluginName, String duration, boolean enabled, boolean allowRandomScheduling) {
        this(pluginName, parseDuration(duration), enabled, allowRandomScheduling);
    }
    private TimeCondition mainTimeStartCondition;
    private static Duration parseDuration(String duration) {
        // If duration is specified, parse it
        if (duration != null && !duration.isEmpty()) {
            try {
                String[] parts = duration.split(":");
                if (parts.length == 2) {
                    int hours = Integer.parseInt(parts[0]);
                    int minutes = Integer.parseInt(parts[1]);
                    return Duration.ofHours(hours).plusMinutes(minutes);                    
                }
            } catch (Exception e) {
                // Invalid duration format, no condition added
                throw new IllegalArgumentException("Invalid duration format: " + duration);
            }
        }
        return null;
    }
   
    public PluginScheduleEntry(String pluginName, Duration interval, boolean enabled, boolean allowRandomScheduling) { //allowRandomScheduling .>allows soft start
        this(pluginName, new IntervalCondition(interval), enabled, allowRandomScheduling);                
    }
    
    public PluginScheduleEntry(String pluginName, TimeCondition startingCondition, boolean enabled, boolean allowRandomScheduling) {
        this(pluginName, startingCondition, enabled, allowRandomScheduling, true);
    }

    public PluginScheduleEntry( String pluginName, 
                                TimeCondition startingCondition, 
                                boolean enabled, 
                                boolean allowRandomScheduling, 
                                boolean autoStartWatchdogs){
        this(pluginName, startingCondition, enabled, allowRandomScheduling, autoStartWatchdogs, true);
                                }
    public PluginScheduleEntry( String pluginName, 
                                TimeCondition startingCondition, 
                                boolean enabled, 
                                boolean allowRandomScheduling, 
                                boolean autoStartWatchdogs,
                                boolean allowContinue
                                ) {
        this.name = pluginName;        
        this.enabled = enabled;
        this.allowRandomScheduling = allowRandomScheduling;
        this.autoStartWatchdogs = autoStartWatchdogs;
        this.cleanName = pluginName.replaceAll("<html>|</html>", "")
                .replaceAll("<[^>]*>([^<]*)</[^>]*>", "$1")
                .replaceAll("<[^>]*>", "");

        this.stopConditionManager = new ConditionManager();
        this.startConditionManager = new ConditionManager();
        
        // Check if this is a default/1-second interval plugin
        boolean isDefaultByScheduleType = false;
        if (startingCondition != null) {
            if (startingCondition instanceof IntervalCondition) {
                IntervalCondition interval = (IntervalCondition) startingCondition;
                if (interval.getInterval().getSeconds() <= 1) {
                    isDefaultByScheduleType = true;
                }
            }
            this.mainTimeStartCondition = startingCondition;
            startConditionManager.setUserLogicalCondition(new OrCondition(startingCondition));
        }
        
        // If it's a default by schedule type, enforce the default settings
        if (isDefaultByScheduleType) {
            this.isDefault = true;
            this.priority = 0;
        }
        //registerPluginConditions();
        scheduleConditionWatchdogs(10000, UpdateOption.SYNC);                
        // Only start watchdogs if auto-start is enabled
        if (autoStartWatchdogs) {
            //stopConditionManager.resumeWatchdogs();
            //startConditionManager.resumeWatchdogs();
        }
        
        // Always register events if enabled
        if (enabled) {            
            startConditionManager.registerEvents();
        }else {
            startConditionManager.unregisterEventsAndPauseWatchdogs();
            stopConditionManager.unregisterEventsAndPauseWatchdogs();
        }     
        this.allowContinue = allowContinue;                   
    }

    /**
     * Creates a scheduled event with a one-time trigger at a specific time
     * 
     * @param pluginName The plugin name
     * @param triggerTime The time when the plugin should trigger once
     * @param enabled Whether the schedule is enabled
     * @return A new PluginScheduleEntry configured to trigger once at the specified time
     */
    public static PluginScheduleEntry createOneTimeSchedule(String pluginName, ZonedDateTime triggerTime, boolean enabled) {
        SingleTriggerTimeCondition condition = new SingleTriggerTimeCondition(triggerTime, Duration.ZERO, 1);
        PluginScheduleEntry entry = new PluginScheduleEntry(
            pluginName, 
            condition, 
            enabled, 
            false); // One-time events are typically not randomized
        
        return entry;
    }

    public void setEnabled(boolean enabled) {
        if (this.enabled == enabled) {
            return; // No change in enabled state
        }
        this.enabled = enabled;
        if (!enabled) {
            stopConditionManager.unregisterEventsAndPauseWatchdogs();
            startConditionManager.unregisterEventsAndPauseWatchdogs();
            runCount = 0;
        } else {
            //stopConditionManager.registerEvents();
            log.debug("registering start events for plugin '{}'", name);
            startConditionManager.registerEvents();
            //log  this object id-> memory hashcode
            log.debug("PluginScheduleEntry {} - {} - {} - {} - {}", this.hashCode(), this.name, this.cleanName, this.enabled, this.allowRandomScheduling);
            //registerPluginConditions();                        
            this.setLastStopReason("");
            this.setLastRunSuccessful(false);
            this.setLastStopReasonType(PluginScheduleEntry.StopReason.NONE);
            
            // Resume watchdogs if they were previously configured and watchdogs are enabled
            if (watchdogsEnabled) {
                startConditionManager.resumeWatchdogs();
                stopConditionManager.resumeWatchdogs();
            }
        }
    }

    /**
     * Controls whether watchdogs are allowed to run for this schedule entry.
     * This provides a way to temporarily disable watchdogs without losing their configuration.
     * 
     * @param enabled true to enable watchdogs, false to disable them
     */
    public void setWatchdogsEnabled(boolean enabled) {
        if (this.watchdogsEnabled == enabled) {
            return; // No change
        }
        
        this.watchdogsEnabled = enabled;
        
        if (enabled) {
            // Resume watchdogs if the plugin is enabled
            if (this.enabled) {
                startConditionManager.resumeWatchdogs();
                stopConditionManager.resumeWatchdogs();
                log.debug("Watchdogs resumed for '{}'", name);
            }
        } else {
            // Pause watchdogs regardless of plugin state
            startConditionManager.pauseWatchdogs();
            stopConditionManager.pauseWatchdogs();
            log.debug("Watchdogs paused for '{}'", name);
        }
    }
    
    /**
     * Checks if watchdogs are currently running for this schedule entry
     * 
     * @return true if at least one watchdog is running
     */
    public boolean areWatchdogsRunning() {
        return startConditionManager.areWatchdogsRunning() || 
               stopConditionManager.areWatchdogsRunning();
    }

    /**
     * Manually start the condition watchdogs for this schedule entry.
     * This will only have an effect if watchdogs are enabled and the plugin is enabled.
     * 
     * @param intervalMillis The interval at which to check for condition changes
     * @param updateOption How to handle condition changes
     * @return true if watchdogs were successfully started
     */
    public boolean startConditionWatchdogs(long intervalMillis, UpdateOption updateOption) {
        if (!watchdogsEnabled || !enabled) {
            return false;
        }
        
        return scheduleConditionWatchdogs(intervalMillis, updateOption);
    }

    /**
     * Stops all watchdogs associated with this schedule entry
     */
    public void stopWatchdogs() {
        log.debug("Stopping all watchdogs for '{}'", name);
        startConditionManager.pauseWatchdogs();
        stopConditionManager.pauseWatchdogs();
    }
    
    public Plugin getPlugin() {
        if (this.plugin == null) {
            this.plugin = Microbot.getPluginManager().getPlugins().stream()
                    .filter(p -> Objects.equals(p.getName(), name))
                    .findFirst()
                    .orElse(null);
            
            // Initialize scheduleEntryConfigManager when plugin is first retrieved
            if (this.plugin instanceof SchedulablePlugin && scheduleEntryConfigManager == null) {
                SchedulablePlugin schedulablePlugin = (SchedulablePlugin) this.plugin;                
                ConfigDescriptor descriptor = schedulablePlugin.getConfigDescriptor();
                if (descriptor != null) {
                    scheduleEntryConfigManager = new ScheduleEntryConfigManager(descriptor);
                }
            }
        }
        return plugin;
    }
    public boolean start(boolean logConditions) {
        if (getPlugin() == null) {
            return false;
        }

        try {
            StringBuilder logBuilder = new StringBuilder();
            logBuilder.append("\nStarting plugin '").append(name).append("':\n");
            
            if (!this.isEnabled()) {
                logBuilder.append(" - Plugin is disabled, not starting\n");
                log.info(logBuilder.toString());
                return false;
            }

            // Log defined conditions when starting
            if (logConditions) {
                logBuilder.append(" - Starting with conditions\n");
                // These methods do their own logging as they're complex and used elsewhere
                logStartConditionsWithDetails();
                logStopConditionsWithDetails();
            }
            
            // Reset stop conditions before starting, if we are not continuing, and we are not interrupted
            if (!this.allowContinue || (lastStopReasonType != StopReason.INTERRUPTED)) {
                logBuilder.append(" - Not continuing, resetting stop conditions\n")
                          .append(" - allowContinue: ").append(allowContinue)
                          .append("\n - last Stop Reason Type: ").append(lastStopReasonType).append("\n");
                resetStopConditions();
            } else {
                logBuilder.append(" - Continuing, not resetting stop conditions\n");
                stopConditionManager.resetPluginConditions();
                
                if (!onLastStopUserConditionsSatisfied && areUserDefinedStopConditionsMet()) {
                    logBuilder.append(" - On last interrupt user stop conditions were not satisfied, now they are, resetting user stop conditions\n");
                    stopConditionManager.resetUserConditions();
                }
            }
            
            if (lastStopReasonType != StopReason.NONE) {
                logBuilder.append(" - Last stop reason: ").append(lastStopReasonType.getDescription())
                          .append("\n - message: ").append(lastStopReason).append("\n");
            }
            
            this.setLastStopReason("");
            this.setLastRunSuccessful(false);
            this.setLastStopReasonType(PluginScheduleEntry.StopReason.NONE);            
            this.setOnLastStopPluginConditionsSatisfied(false);
            this.setOnLastStopUserConditionsSatisfied(false);
            
            // Set scheduleMode to true in plugin config
            if (scheduleEntryConfigManager != null) {
                scheduleEntryConfigManager.setScheduleMode(true);
                logBuilder.append(" - Set \"scheduleMode\" in config of the plugin\n");
            }
            
            Microbot.getClientThread().runOnSeperateThread(() -> {
                Plugin plugin = getPlugin();
                if (plugin == null) {
                    log.error("Plugin '{}' not found -> can't start plugin", name);
                    return false;
                }                
                Microbot.startPlugin(plugin);
                return false;
            });
            
            stopInitiated = false;
            hasStarted = true;
            lastRunDuration = Duration.ZERO; // Reset last run duration
            lastRunStartTime = ZonedDateTime.now(); // Set the start time of the last run
            
            // Register/unregister appropriate event handlers
            logBuilder.append(" - Registering stopping conditions\n");
            stopConditionManager.registerEvents();
            
            logBuilder.append(" - Unregistering start conditions\n");
            startConditionManager.unregisterEvents();
            
            // Log all collected information at once
            log.info(logBuilder.toString());
            
            return true;
        } catch (Exception e) {
            log.error("Error starting plugin '{}': {}", name, e.getMessage(), e);
            return false;
        }
    }
    
    /**
     * Initiates a graceful (soft) stop of the plugin.
     * <p>
     * This method notifies the plugin that it should stop via a {@link PluginScheduleEntrySoftStopEvent},
     * allowing the plugin to finish critical operations before shutting down. It also:
     * <ul>
     *   <li>Resets and re-registers start condition monitors</li>
     *   <li>Unregisters stop condition monitors</li>
     *   <li>Records timing information about the stop attempt</li>
     *   <li>Starts a monitoring thread to track the stopping process</li>
     * </ul>
     * <p>
     * After sending the stop event, the plugin is responsible for handling its own shutdown.
     * 
     * @param successfulRun indicates whether the plugin completed its task successfully
     */
    private void softStop(boolean successfulRun) {
        if (getPlugin() == null) {
            return;
        }

        try {
            // Reset start conditions            
            startConditionManager.registerEvents();            
            stopConditionManager.unregisterEvents();
            
            Microbot.getClientThread().runOnClientThreadOptional(() -> {
                ZonedDateTime current_time = ZonedDateTime.now(ZoneId.systemDefault());
                Microbot.getEventBus().post(new PluginScheduleEntrySoftStopEvent(plugin, current_time));
                return true;                
            });
            if(!stopInitiated){
                this.stopInitiated = true;
                this.stopInitiatedTime = ZonedDateTime.now();
            }            
            // If no custom stop reason was set, use the default reason from the enum
            if (lastStopReason == null && lastStopReasonType != null) {
                lastStopReason = lastStopReasonType.getDescription();
            }
            this.lastStopAttemptTime = ZonedDateTime.now();
            this.lastRunDuration = Duration.between(lastRunStartTime, ZonedDateTime.now());
            this.lastRunEndTime = ZonedDateTime.now();
            // Start monitoring for successful stop
            startStopMonitoringThread(successfulRun);            

            if (getPlugin() instanceof SchedulablePlugin) {
                log.info("soft stopping  for plugin '{}'", name);
            }
            return;
        } catch (Exception e) {
            return;
        }
    }

    /**
     * Forces an immediate (hard) stop of the plugin.
     * <p>
     * This method is used when a soft stop has failed or timed out and the plugin
     * needs to be forcibly terminated. It directly calls Microbot's stopPlugin method
     * to immediately terminate the plugin's execution.
     * <p>
     * Hard stops should only be used as a last resort when soft stops fail, as they
     * don't allow the plugin to perform cleanup operations or save state.
     * 
     * @param successfulRun indicates whether to record this run as successful
     */
    private void hardStop(boolean successfulRun) {
        if (getPlugin() == null) {
            return;
        }

        try {
            Microbot.getClientThread().runOnSeperateThread(() -> {
                log.info("Hard stopping plugin '{}'", name);
                Plugin stopPlugin = Microbot.getPlugin(plugin.getClass().getName());
                Microbot.stopPlugin(stopPlugin);
                return false;
            });
            if(!stopInitiated){
                stopInitiated = true;
                stopInitiatedTime = ZonedDateTime.now();
            }
            lastStopAttemptTime = ZonedDateTime.now();            
            // Set these fields to match what softStop does
            lastRunDuration = Duration.between(lastRunStartTime, ZonedDateTime.now());
            lastRunEndTime = ZonedDateTime.now();            
            // Also set a descriptive stop reason if one isn't already set
            if (lastStopReason == null) {
                lastStopReason = lastStopReasonType != null && lastStopReasonType == StopReason.HARD_STOP 
                    ? lastStopReasonType.getDescription() 
                    : "Plugin was forcibly stopped after not responding to soft stop";
            }            
            // Start monitoring for successful stop
            startStopMonitoringThread(successfulRun);
            
            return;
        } catch (Exception e) {
            return;
        }
    }

     /**
     * Starts a monitoring thread that tracks the stopping process of a plugin.
     * <p>
     * This method creates a daemon thread that periodically checks if a plugin
     * that is in the process of stopping has completed its shutdown. When the plugin
     * successfully stops, this method updates the next scheduled run time and clears
     * all stopping-related state flags.
     * <p>
     * The monitoring thread will only be started if one is not already running
     * (controlled by the isMonitoringStop flag). It checks the plugin's running state
     * every 500ms until the plugin stops or monitoring is canceled.
     * <p>
     * The thread is created as a daemon thread to prevent it from blocking JVM shutdown.
     */
    private void startStopMonitoringThread(boolean successfulRun) {
        // Don't start a new thread if one is already running
        if (isMonitoringStop) {
            return;
        }
        
        isMonitoringStop = true;
        
        stopMonitorThread = new Thread(() -> {
            StringBuilder logMsg = new StringBuilder();
            logMsg.append("\n\tMonitoring thread started for stopping the plugin '").append(getCleanName()).append("' ");
            log.info(logMsg.toString());
            
            try {
                // Keep checking until the stop completes or is abandoned
                while (stopInitiated && isMonitoringStop) {
                    // Check if plugin has stopped running
                    if (!isRunning()) {
                        logMsg = new StringBuilder();
                        logMsg.append("\nPlugin '").append(getCleanName()).append("' has successfully stopped")
                             .append(" - updating state - successfulRun ").append(successfulRun);
                        
                        // Set scheduleMode back to false when the plugin stops
                        if (scheduleEntryConfigManager != null) {
                            scheduleEntryConfigManager.setScheduleMode(false);
                            logMsg.append("\n unset \"scheduleMode\" - flag in the config. of the plugin '").append(getCleanName()).append("'");
                        }
                        
                        
                        
                        break;
                    }
                    else {
                        // Plugin is still running, log the status
                        if (stopInitiatedTime != null && Duration.between(stopInitiatedTime, ZonedDateTime.now()).getSeconds()% 60==0) {
                            logMsg = new StringBuilder();
                            logMsg.append("\nPlugin '").append(getCleanName()).append("' is still running");
                            logMsg.append("\n- stop initiated at: ").append(stopInitiatedTime.format(DATE_TIME_FORMATTER))
                                  .append("\n- current time: ").append(ZonedDateTime.now().format(DATE_TIME_FORMATTER));
                            logMsg.append("\n- elapsed time: ").append(Duration.between(stopInitiatedTime, ZonedDateTime.now()).toSeconds())
                                  .append(" sec - successfulRun ").append(successfulRun);
                            log.info(logMsg.toString());
                        }
                        stop(successfulRun); // Call the stop method to handle any additional logic
                    }
                    
                    // Check every 600ms to be responsive but not wasteful
                    Thread.sleep(600);
                }
            } catch (InterruptedException e) {
                // Thread was interrupted, just exit
                log.info("\n\tStop monitoring thread for '" + name + "' was interrupted");
            } finally {                
                // Update lastRunTime and start conditions for next run
                if (successfulRun) {
                    resetStartConditions();                            
                } else {
                    setEnabled(false); // disable the plugin if it was not successful?
                }
                log.info(logMsg.toString());
                logStopConditionsWithDetails();                
                // Reset stop state
                stopInitiated = false;
                hasStarted = false;
                stopInitiatedTime = null;
                lastStopAttemptTime = null;                
                // Invoke the stop completion callback if one is registered
                if (stopCompletionCallback != null) {
                    try {
                        stopCompletionCallback.onStopCompleted(PluginScheduleEntry.this, successfulRun);
                        log.debug("Stop completion callback executed for plugin '{}'", name);
                    } catch (Exception e) {
                        log.error("Error executing stop completion callback for plugin '{}'", name, e);
                    }
                }               
                log.info("Stop monitoring thread exited for plugin '" + name + "'");
            }
        });
        
        stopMonitorThread.setName("StopMonitor-" + name);
        stopMonitorThread.setDaemon(true); // Use daemon thread to not prevent JVM exit
        stopMonitorThread.start();
        
    }
    public void cancelStop(){  
        log.info ("Cancelling stop for plugin '{}'", name);
        if (isMonitoringStop && stopMonitorThread != null) {
            stopMonitorThread.interrupt(); // Interrupt the monitoring thread
            stopMonitorThread = null; // Clear the reference
        }
        
        stopInitiated = false;        
        stopInitiatedTime = null;
        lastStopAttemptTime = null;
    }

    /**
     * Stops the monitoring thread if it's running
     */
    private void stopMonitoringThread() {
        if (isMonitoringStop && stopMonitorThread != null) {
            log.info("Stopping monitoring thread for plugin '{}'", name);
            isMonitoringStop = false;
            stopMonitorThread.interrupt();
            stopMonitorThread = null;
        }
    }

    /**
     * Checks if this plugin schedule has any defined stop conditions
     * 
     * @return true if at least one stop condition is defined
     */
    public boolean hasAnyStopConditions() {
        return stopConditionManager != null && 
               !stopConditionManager.getConditions().isEmpty();
    }
    
    /**
     * Checks if this plugin has any one-time stop conditions that can only trigger once
     * 
     * @return true if at least one single-trigger condition exists in the stop conditions
     */
    public boolean hasAnyOneTimeStopConditions() {
        return stopConditionManager != null && 
               stopConditionManager.hasAnyOneTimeConditions();
    }
    
    /**
     * Checks if any stop conditions have already triggered and cannot trigger again
     * 
     * @return true if at least one stop condition has triggered and cannot trigger again
     */
    public boolean hasTriggeredOneTimeStopConditions() {
        return stopConditionManager != null && 
               stopConditionManager.hasTriggeredOneTimeConditions();
    }
    
    /**
     * Determines if the stop conditions can trigger again in the future
     * Considers the nested logical structure and one-time conditions
     * 
     * @return true if the stop condition structure can trigger again
     */
    public boolean canStopTriggerAgain() {
        return stopConditionManager != null && 
               stopConditionManager.canTriggerAgain();
    }
    
    /**
     * Gets the next time when any stop condition is expected to trigger
     * 
     * @return Optional containing the next stop trigger time, or empty if none exists
     */
    public Optional<ZonedDateTime> getNextStopTriggerTime() {
        if (stopConditionManager == null) {
            return Optional.empty();
        }
        return stopConditionManager.getCurrentTriggerTime();
    }
    
    /**
     * Gets a human-readable string representing when the next stop condition will trigger
     * 
     * @return String with the time until the next stop trigger, or a message if none exists
     */
    public String getNextStopTriggerTimeString() {
        if (stopConditionManager == null) {
            return "No stop conditions defined";
        }
        return stopConditionManager.getCurrentTriggerTimeString();
    }
    
    /**
     * Checks if the stop conditions are fulfillable based on their structure and state
     * A condition is considered unfulfillable if it contains one-time conditions that
     * have all already triggered in an OR structure, or if any have triggered in an AND structure
     * 
     * @return true if the stop conditions can still be fulfilled
     */
    public boolean hasFullfillableStopConditions() {
        if (!hasAnyStopConditions()) {
            return false;
        }
        
        // If we have any one-time conditions that can't trigger again
        // and the structure is such that it can't satisfy anymore, then it's not fulfillable
        if (hasAnyOneTimeStopConditions() && !canStopTriggerAgain()) {
            return false;
        }
        
        return true;
    }
    
    /**
     * Gets the remaining duration until the next stop condition trigger
     * 
     * @return Optional containing the duration until next stop trigger, or empty if none available
     */
    public Optional<Duration> getDurationUntilStopTrigger() {
        if (stopConditionManager == null) {
            return Optional.empty();
        }
        return stopConditionManager.getDurationUntilNextTrigger();
    }
    
       
    

    /**
     * Determines whether this plugin is currently running.
     * <p>
     * This method checks if the plugin is enabled in the RuneLite plugin system.
     * It uses the Microbot API to query if the plugin associated with this schedule
     * entry is currently in an active/running state.
     *
     * @return true if the plugin is currently running, false otherwise
     */
    public boolean isRunning() {                
        Plugin plugin = getPlugin();
        if (plugin != null) {
            return Microbot.isPluginEnabled(plugin.getClass()) && hasStarted;
        }
        return false; 
    }
    
    public boolean isStopped() {                
        Plugin plugin = getPlugin();
        if (plugin != null) {
            return !Microbot.isPluginEnabled(plugin.getClass()) && !stopInitiated;
        }
        return false; 
    }
    public boolean isStopping() {                      
        return stopInitiated; 
    }

    /**
     * Round time to nearest minute (remove seconds and milliseconds)
     */
    private ZonedDateTime roundToMinutes(ZonedDateTime time) {
        return time.withSecond(0).withNano(0);
    }
    private void logStartCondtions() {
        List<Condition> conditionList = startConditionManager.getConditions();
        logConditionInfo(conditionList,"Defined Start Conditions", true);
    }
    private void logStartConditionsWithDetails() {
        List<Condition> conditionList = startConditionManager.getConditions();
        logConditionInfo(conditionList,"Defined Start Conditions", true);
    }

    /**
     * Checks if this plugin schedule has any defined start conditions
     * 
     * @return true if at least one start condition is defined
     */
    public boolean hasAnyStartConditions() {
        return startConditionManager != null && 
               !startConditionManager.getConditions().isEmpty();
    }
    
    /**
     * Checks if this plugin has any one-time start conditions that can only trigger once
     * 
     * @return true if at least one single-trigger condition exists in the start conditions
     */
    public boolean hasAnyOneTimeStartConditions() {
        return startConditionManager != null && 
               startConditionManager.hasAnyOneTimeConditions();
    }
    
    /**
     * Checks if any start conditions have already triggered and cannot trigger again
     * 
     * @return true if at least one start condition has triggered and cannot trigger again
     */
    public boolean hasTriggeredOneTimeStartConditions() {
        return startConditionManager != null && 
               startConditionManager.hasTriggeredOneTimeConditions();
    }
    
    /**
     * Determines if the start conditions can trigger again in the future
     * Considers the nested logical structure and one-time conditions
     * 
     * @return true if the start condition structure can trigger again
     */
    public boolean canStartTriggerAgain() {
        return startConditionManager != null && 
               startConditionManager.canTriggerAgain();
    }
    
    /**
     * Gets the next time when any start condition is expected to trigger
     * 
     * @return Optional containing the next start trigger time, or empty if none exists
     */
    public Optional<ZonedDateTime> getCurrentStartTriggerTime() {
        if (startConditionManager == null) {
            return Optional.empty();
        }
        return startConditionManager.getCurrentTriggerTime();
    }
    
    /**
     * Gets a human-readable string representing when the next start condition will trigger
     * 
     * @return String with the time until the next start trigger, or a message if none exists
     */
    public String getCurrentStartTriggerTimeString() {
        if (startConditionManager == null) {
            return "No start conditions defined";
        }
        return startConditionManager.getCurrentTriggerTimeString();
    }
    
    /**
     * Checks if the start conditions are fulfillable based on their structure and state
     * A condition is considered unfulfillable if it contains one-time conditions that
     * have all already triggered in an OR structure, or if any have triggered in an AND structure
     * 
     * @return true if the start conditions can still be fulfilled
     */
    public boolean hasFullfillableStartConditions() {
        if (!hasAnyStartConditions()) {
            return false;
        }
        
        // If we have any one-time conditions that can't trigger again
        // and the structure is such that it can't satisfy anymore, then it's not fulfillable
        if (hasAnyOneTimeStartConditions() && !canStartTriggerAgain()) {
            return false;
        }
        
        return true;
    }
    
    /**
     * Gets the remaining duration until the next start condition trigger
     * 
     * @return Optional containing the duration until next start trigger, or empty if none available
     */
    public Optional<Duration> getDurationUntilStartTrigger() {
        if (startConditionManager == null) {
            return Optional.empty();
        }
        return startConditionManager.getDurationUntilNextTrigger();
    }
    /**
     * Gets a detailed description of the stop conditions status
     * 
     * @return A string with detailed information about stop conditions
     */
    public String getDetailedStopConditionsStatus() {
        if (!hasAnyStopConditions()) {
            return "No stop conditions defined";
        }
        
        StringBuilder sb = new StringBuilder("Stop conditions: ");
        
        // Add logic type
        sb.append(stopConditionManager.requiresAll() ? "ALL must be met" : "ANY can be met");
        
        // Add fulfillability status
        if (!hasFullfillableStopConditions()) {
            sb.append(" (UNFULFILLABLE)");
        }
        
        // Add condition count
        int total = getTotalStopConditionCount();
        int satisfied = getSatisfiedStopConditionCount();
        sb.append(String.format(" - %d/%d conditions met", satisfied, total));
        
        // Add next trigger time if available
        Optional<ZonedDateTime> nextTrigger = getNextStopTriggerTime();
        if (nextTrigger.isPresent()) {
            sb.append(" - Next trigger: ").append(getNextStopTriggerTimeString());
        }
        
        return sb.toString();
    }
    /**
     * Gets a detailed description of the start conditions status
     * 
     * @return A string with detailed information about start conditions
     */
    public String getDetailedStartConditionsStatus() {
        if (!hasAnyStartConditions()) {
            return "No start conditions defined";
        }
        
        StringBuilder sb = new StringBuilder("Start conditions: ");
        
        // Add logic type
        sb.append(startConditionManager.requiresAll() ? "ALL must be met" : "ANY can be met");
        
        // Add fulfillability status
        if (!hasFullfillableStartConditions()) {
            sb.append(" (UNFULFILLABLE)");
        }
        
        // Add condition count and satisfaction status
        int totalStartConditions = startConditionManager.getConditions().size();
        long satisfiedStartConditions = startConditionManager.getConditions().stream()
                .filter(Condition::isSatisfied)
                .count();
        sb.append(String.format(" - %d/%d conditions met", satisfiedStartConditions, totalStartConditions));
        
        // Add next trigger time if available
        Optional<ZonedDateTime> nextTrigger = getCurrentStartTriggerTime();
        if (nextTrigger.isPresent()) {
            sb.append(" - Next trigger: ").append(getCurrentStartTriggerTimeString());
        }
        
        return sb.toString();
    }
    
    /**
     * Determines if the plugin should be started immediately based on its current
     * start condition status
     * 
     * @return true if the plugin should be started immediately
     */
    public boolean shouldStartImmediately() {
        // If no start conditions, don't start automatically
        if (!hasAnyStartConditions()) {
            return false;
        }
        
        // If start conditions are met, start the plugin
        if (areUserStartConditionsMet() && arePluginStartConditionsMet()) {
            if ( startConditionManager.getConditions().isEmpty()){
                log.info("Plugin '{}' has no start conditions defined, starting immediately", name);
                return false;
            }
            return true;
        }
        
        return false;
    }
    public boolean canBeStarted() {
        // If no start conditions, don't start automatically
        if (isRunning()) {
            return false;
        }
        if(!isEnabled()){
            return false;
        }
        

        // If start conditions are met, start the plugin
        if (areUserStartConditionsMet() && arePluginStartConditionsMet()) {
            return true;
        }
        
                return false;
       
    }
    
    /**
     * Logs the defined start conditions with their current states
     */
    private void logDefinedStartConditionWithStates() {
        logStartConditionsWithDetails();
        
        // If the conditions are unfulfillable, log a warning
        if (!hasFullfillableStartConditions()) {
            log.warn("Plugin {} has unfulfillable start conditions - may not start properly", name);
        }
        
        // Log progress percentage
        double progress = startConditionManager.getProgressTowardNextTrigger();
        log.info("Plugin {} start condition progress: {:.2f}%", name, progress);
    }
    
    /**
    * Updates the isDueToRun method to use the diagnostic helper for logging
    */
    public boolean isDueToRun() {
        // Check if we're already running
        if (isRunning()) {
            return false;
        }
        
        // For plugins with start conditions, check if those conditions are met
        if (!hasAnyStartConditions()) {
            //log.info("No start conditions defined for plugin '{}'", name);
            return false;
        }
        
        
        
        // Log at appropriate levels
        if (Microbot.isDebug()) {
            // Build comprehensive log info using our diagnostic helper
            String diagnosticInfo = diagnoseStartConditions();
            // In debug mode, log the full detailed diagnostics
            log.debug("\n[isDueToRun] - \n"+diagnosticInfo);
        }
          
        
        // Check if start conditions are met
        return startConditionManager.areAllConditionsMet();
    }    

    /**
    * Updates the primary time condition for this plugin schedule entry.
    * This method replaces the original time condition that was added when the entry was created,
    * but preserves any additional conditions that might have been added later.
    * 
    * @param newTimeCondition The new time condition to use
    * @return true if a time condition was found and replaced, false otherwise
    */
    public boolean updatePrimaryTimeCondition(TimeCondition newTimeCondition) {
        if (startConditionManager == null || newTimeCondition == null) {
            return false;
        }     
        startConditionManager.pauseWatchdogs();           
        // First, find the existing time condition. We'll assume the first time condition 
        // we find is the primary one that was added at creation
        TimeCondition existingTimeCondition = this.mainTimeStartCondition;                
        
        // If we found a time condition, replace it
        if (existingTimeCondition != null) {
            Optional<ZonedDateTime> currentTrigDateTime = existingTimeCondition.getCurrentTriggerTime();
            Optional<ZonedDateTime> newTrigDateTime = newTimeCondition.getCurrentTriggerTime();
            log.debug("Replacing time condition {} with {}", 
                    existingTimeCondition.getDescription(), 
                    newTimeCondition.getDescription());
            
            
            boolean isDefaultByScheduleType = this.isDefault();
          
            
            // Check if new condition is a one-second interval (default)
            boolean willBeDefaultByScheduleType = false;
            if (newTimeCondition instanceof IntervalCondition) {
                IntervalCondition intervalCondition = (IntervalCondition) newTimeCondition;
                if (intervalCondition.getInterval().getSeconds() <= 1) {
                    willBeDefaultByScheduleType = true;
                }
            }
            
            // Remove the existing condition and add the new one
            if (startConditionManager.removeCondition(existingTimeCondition)) {
                if (!startConditionManager.containsCondition(newTimeCondition)) {
                    startConditionManager.addUserCondition(newTimeCondition);
                }
                
                // Update default status if needed
                if (willBeDefaultByScheduleType) {
                    //this.setDefault(true);
                    //this.setPriority(0);
                } else if (isDefaultByScheduleType && !willBeDefaultByScheduleType) {
                    // Only change from default if it was set automatically by condition type
                    //this.setDefault(false);
                }                
                
                this.mainTimeStartCondition = newTimeCondition;                                
            }
            if (currentTrigDateTime.isPresent() && newTrigDateTime.isPresent()) {
                // Check if the new trigger time is different from the current one
                if (!currentTrigDateTime.get().equals(newTrigDateTime.get())) {
                    log.debug("\n\tUpdated main start time for Plugin'{}'\nfrom {}\nto {}", 
                            name, 
                            currentTrigDateTime.get().format(DATE_TIME_FORMATTER),
                            newTrigDateTime.get().format(DATE_TIME_FORMATTER));                    
                } else {
                    log.debug("\n\tStart next time for Pugin '{}' remains unchanged", name);
                }
            }
        } else {
            // No existing time condition found, just add the new one
            log.info("No existing time condition found, adding new condition: {}", 
                    newTimeCondition.getDescription());
            // Check if the condition already exists before adding it
            if (startConditionManager.containsCondition(newTimeCondition)) {
                log.info("Condition {} already exists in the manager, not adding a duplicate", 
                newTimeCondition.getDescription());
                // Still need to update start conditions in case the existing one needs resetting                                                
            }else{
                startConditionManager.addUserCondition(newTimeCondition);
            }            
            this.mainTimeStartCondition = newTimeCondition;                 
            //updateStartConditions();// we have new condition ->  new start time ?
        }     
        startConditionManager.resumeWatchdogs();   
        return true;
    }

    /**
     * Update the lastRunTime to now and reset start conditions
     */
    private void resetStartConditions() {        
        if (startConditionManager == null) {
            return;
        }
        
        StringBuilder logMsg = new StringBuilder("\n");
        Optional<ZonedDateTime> nextTriggerTimeBeforeReset = getCurrentStartTriggerTime();
        
        logMsg.append("Updating start conditions for plugin '").append(getCleanName()).append("'");
        logMsg.append("\n  -last stop reason: ").append(lastStopReasonType.getDescription());
        logMsg.append("\n  -last stop reason message:\n\t").append(lastStopReason);
        logMsg.append("\n  -allowContinue: ").append(allowContinue);
        logMsg.append("\n  -last run duration: ").append(lastRunDuration.toMillis()).append(" ms");
        if (this.lastStopReasonType != StopReason.INTERRUPTED || !allowContinue) {
    
            logMsg.append("\n  -Completed successfully, resetting all start conditions");
            startConditionManager.reset();
            // Increment the run count since we completed a full run
            incrementRunCount();
        } else {
            logMsg.append("\n  -Only resetting plugin '").append(getCleanName()).append("' start conditions");
            startConditionManager.resetPluginConditions();
        }
        
        Optional<ZonedDateTime> triggerTimeAfterReset = getCurrentStartTriggerTime();
        
        // Update the nextRunTime for legacy compatibility if possible
        if (triggerTimeAfterReset.isPresent()) {
            ZonedDateTime nextRunTime = triggerTimeAfterReset.get();
            logMsg.append("\n  - Updated run time for Plugin '").append(getCleanName()).append("'")
                  .append("\n    Before: ").append(nextTriggerTimeBeforeReset.map(t -> t.format(DATE_TIME_FORMATTER)).orElse("N/A"))
                  .append("\n    After:  ").append(nextRunTime.format(DATE_TIME_FORMATTER));
        } else if (hasTriggeredOneTimeStartConditions() && !canStartTriggerAgain()) {
            logMsg.append("\n  - One-time conditions triggered, not scheduling next run");
        }
        
        // Output the consolidated log message
        log.info(logMsg.toString());
    }

    /**
     * Reset stop conditions
     */
    private void resetStopConditions() {
        if (!stopInitiated){
            log.info("resetting stop conditions on start up of plugin '{}'", name);
            if (stopConditionManager != null) {
                stopConditionManager.reset();            
                // Log that stop conditions were reset
                log.debug("Reset stop conditions for plugin '{}'", name);
            }
        }else{

        }
    }
     /**
     * Reset stop conditions
     */
    public void hardResetConditions() {
                
        if (stopConditionManager != null) {
            stopConditionManager.hardResetUserConditions();            
            // Log that stop conditions were reset
            log.debug("Hard Reset stop conditions for plugin '{}'", name);
        }
        if (startConditionManager != null) {
            startConditionManager.hardResetUserConditions();            
            // Log that stop conditions were reset
            log.debug("Hard Reset start conditions for plugin '{}'", name);
        }
        
    }


    
    /**
     * Get a formatted display of the scheduling interval
     */
    public String getIntervalDisplay() {
        if (!hasAnyStartConditions()) {
            return "No schedule defined";
        }
        
        List<TimeCondition> timeConditions = startConditionManager.getTimeConditions();
        if (timeConditions.isEmpty()) {
            return "Non-time conditions only";
        }
        
        // Check for common condition types
        if (timeConditions.size() == 1) {
            TimeCondition condition = timeConditions.get(0);
            
            return getTimeDisplayFromTimeCondition(condition);
        }
        
        // If we have multiple time conditions, find the one that will trigger first
        if (timeConditions.size() > 1) {
            TimeCondition earliestTriggerCondition = findEarliestTriggerTimeCondition(timeConditions);
            if (earliestTriggerCondition != null) {
                return getTimeDisplayFromTimeCondition(earliestTriggerCondition) + " (Next to trigger)";
            }
        }
        
        // If we have multiple time conditions or other complex scenarios but couldn't determine earliest
        return "Complex time schedule";
    }
    
    /**
     * Finds the time condition that will trigger first among a list of time conditions
     * 
     * @param timeConditions List of time conditions to check
     * @return The time condition that will trigger first, or null if none is found
     */
    private TimeCondition findEarliestTriggerTimeCondition(List<TimeCondition> timeConditions) {
        ZonedDateTime earliestTriggerTime = null;
        TimeCondition earliestCondition = null;
        
        for (TimeCondition condition : timeConditions) {
            Optional<ZonedDateTime> triggerTime = condition.getCurrentTriggerTime();
            if (triggerTime.isPresent()) {
                ZonedDateTime nextTrigger = triggerTime.get();
                
                // If this is the first valid trigger time we've found, or it's earlier than our current earliest
                if (earliestTriggerTime == null || nextTrigger.isBefore(earliestTriggerTime)) {
                    earliestTriggerTime = nextTrigger;
                    earliestCondition = condition;
                }
            }
        }
        
        return earliestCondition;
    }
    private String getTimeDisplayFromTimeCondition(TimeCondition condition) {
        if (condition instanceof SingleTriggerTimeCondition) {
            Optional<ZonedDateTime> triggerTime = ((SingleTriggerTimeCondition) condition).getNextTriggerTimeWithPause();
            if (!triggerTime.isPresent()) {
                return "No trigger time available";
            }
            return "Once at " + triggerTime.get().format(DATE_TIME_FORMATTER);
        } 
        else if (condition instanceof IntervalCondition) {
            return formatIntervalCondition((IntervalCondition) condition);
        }
        else if (condition instanceof TimeWindowCondition) {
            return formatTimeWindowCondition((TimeWindowCondition) condition);
        }
        else if (condition instanceof DayOfWeekCondition) {
            return formatDayOfWeekCondition((DayOfWeekCondition) condition);
        }
        return "Unknown time condition type: " + condition.getClass().getSimpleName();
    }
    
    /**
     * Formats an interval condition into a user-friendly string
     */
    private String formatIntervalCondition(IntervalCondition condition) {
        Duration avgInterval = condition.getInterval();
        Duration minInterval = condition.getMinInterval();
        Duration maxInterval = condition.getMaxInterval();
        boolean isRandomized = condition.isRandomize();
        
        if (!isRandomized) {
            return formatTimeRange(avgInterval, null, false);
        } else {
            return "Randomized " + formatTimeRange(minInterval, maxInterval, true);
        }
    }
    
    /**
     * Formats a time window condition into a user-friendly string
     */
    private String formatTimeWindowCondition(TimeWindowCondition condition) {
        LocalTime startTime = condition.getStartTime();
        LocalTime endTime = condition.getEndTime();
        String timesStr = String.format("%s-%s", 
                startTime.format(DateTimeFormatter.ofPattern("HH:mm")),
                endTime.format(DateTimeFormatter.ofPattern("HH:mm")));
        
        // Check repeat cycle
        String cycleStr = "";
        boolean useRandomization = false;
        
        try {
            useRandomization = condition.isUseRandomization();
            RepeatCycle repeatCycle = condition.getRepeatCycle();
            int interval = condition.getRepeatIntervalUnit();
            
            switch (repeatCycle) {
                case DAYS:
                    cycleStr = (interval == 1) ? "daily" : "every " + interval + " days";
                    break;
                case WEEKS:
                    cycleStr = (interval == 1) ? "weekly" : "every " + interval + " weeks";
                    break;
                case HOURS:
                    cycleStr = (interval == 1) ? "hourly" : "every " + interval + " hours";
                    break;
                case MINUTES:
                    cycleStr = (interval == 1) ? "every minute" : "every " + interval + " minutes";
                    break;
                case ONE_TIME:
                    cycleStr = "once";
                    break;
                default:
                    cycleStr = "daily";
            }
        } catch (Exception e) {
            // Fallback if we can't access some property
            cycleStr = "daily";
        }
        
        return useRandomization 
                ? String.format("Randomized %s %s", timesStr, cycleStr)
                : String.format("%s %s", timesStr, cycleStr);
    }
    
    /**
     * Formats a day of week condition into a user-friendly string
     */
    private String formatDayOfWeekCondition(DayOfWeekCondition condition) {
        Set<DayOfWeek> activeDays = condition.getActiveDays();
        
        // Format day names
        StringBuilder daysStr = new StringBuilder();
        
        if (activeDays.size() == 7) {
            daysStr.append("Every day");
        } else if (activeDays.size() == 5 && activeDays.contains(DayOfWeek.MONDAY) && 
                activeDays.contains(DayOfWeek.TUESDAY) && activeDays.contains(DayOfWeek.WEDNESDAY) &&
                activeDays.contains(DayOfWeek.THURSDAY) && activeDays.contains(DayOfWeek.FRIDAY)) {
            daysStr.append("Weekdays");
        } else if (activeDays.size() == 2 && activeDays.contains(DayOfWeek.SATURDAY) && 
                activeDays.contains(DayOfWeek.SUNDAY)) {
            daysStr.append("Weekends");
        } else {
            List<String> dayNames = new ArrayList<>();
            for (DayOfWeek day : activeDays) {
                // Convert to short day name (Mon, Tue, etc.)
                String dayName = day.toString().substring(0, 3);
                dayNames.add(dayName.charAt(0) + dayName.substring(1).toLowerCase());
            }
            // Sort days in week order (Monday first)
            Collections.sort(dayNames);
            daysStr.append(String.join("/", dayNames));
        }
        
        // Check if it has an interval condition
        if (condition.hasIntervalCondition()) {
            Optional<IntervalCondition> intervalOpt = condition.getIntervalCondition();
            if (intervalOpt.isPresent()) {
                IntervalCondition interval = intervalOpt.get();
                
                // Add interval info
                if (interval.isRandomize()) {
                    Duration minInterval = interval.getMinInterval();
                    Duration maxInterval = interval.getMaxInterval();
                    daysStr.append(", random ").append(formatTimeRange(minInterval, maxInterval, true));
                } else {
                    Duration avgInterval = interval.getInterval();
                    daysStr.append(", ").append(formatTimeRange(avgInterval, null, false));
                }
            }
        }
        
        // Add max repeats information if applicable
        long maxPerDay = condition.getMaxRepeatsPerDay();
        long maxPerWeek = condition.getMaxRepeatsPerWeek();
        
        if (maxPerDay > 0 || maxPerWeek > 0) {
            daysStr.append(" (");
            boolean needsComma = false;
            
            if (maxPerDay > 0) {
                daysStr.append("max ").append(maxPerDay).append("/day");
                needsComma = true;
            }
            
            if (maxPerWeek > 0) {
                if (needsComma) {
                    daysStr.append(", ");
                }
                daysStr.append("max ").append(maxPerWeek).append("/week");
            }
            
            daysStr.append(")");
        }
        
        return daysStr.toString();
    }
    
    /**
     * Helper to format time durations in a user-friendly string
     */
    private String formatTimeRange(Duration duration, Duration maxDuration, boolean isRange) {
        if (duration == null) {
            return "unknown interval";
        }
        
        long hours = duration.toHours();
        long minutes = duration.toMinutes() % 60;
        
        if (!isRange) {
            if (hours > 0) {
                return String.format("every %d hour%s%s", 
                        hours, 
                        hours > 1 ? "s" : "",
                        minutes > 0 ? " " + minutes + " min" : "");
            } else {
                return String.format("every %d minute%s", 
                        minutes, 
                        minutes > 1 ? "s" : "");
            }
        } else {
            // Format a range - "every X to Y hours/minutes"
            if (maxDuration == null) {
                return formatTimeRange(duration, null, false);
            }
            
            long maxHours = maxDuration.toHours();
            long maxMinutes = maxDuration.toMinutes() % 60;
            
            if (hours > 0) {
                if (maxHours > 0) {
                    // Both have hours component
                    String minStr = String.format("%d hour%s%s", 
                            hours, 
                            hours > 1 ? "s" : "",
                            minutes > 0 ? " " + minutes + "m" : "");
                    
                    String maxStr = String.format("%d hour%s%s", 
                            maxHours, 
                            maxHours > 1 ? "s" : "",
                            maxMinutes > 0 ? " " + maxMinutes + "m" : "");
                    
                    return String.format("every %s to %s", minStr, maxStr);
                } else {
                    // Min has hours but max only has minutes
                    return String.format("every %d hour%s%s to %d minutes", 
                            hours, 
                            hours > 1 ? "s" : "",
                            minutes > 0 ? " " + minutes + "m" : "",
                            maxMinutes);
                }
            } else {
                if (maxHours > 0) {
                    // Min has only minutes but max has hours
                    return String.format("every %d minutes to %d hour%s%s", 
                            minutes,
                            maxHours, 
                            maxHours > 1 ? "s" : "",
                            maxMinutes > 0 ? " " + maxMinutes + "m" : "");
                } else {
                    // Both only have minutes
                    return String.format("every %d to %d minute%s", 
                            minutes,
                            maxMinutes,
                            maxMinutes > 1 ? "s" : "");
                }
            }
        }
    }


    /**
     * Gets the time remaining until the next plugin
     * 
     * @return Duration until next plugin or null if no plugins scheduled
     */
    public Optional<Duration> getTimeUntilNextRun() {
        if (!enabled) {
            return Optional.empty();            
        }        
        // Get the next trigger time for this plugin
        Optional<ZonedDateTime> nextTriggerTime = this.getCurrentStartTriggerTime();
        if (!nextTriggerTime.isPresent()) {
            // If no trigger time is available, return empty
            return Optional.empty();
        }

        // Calculate time until trigger
        return Optional.of(Duration.between(ZonedDateTime.now(ZoneId.systemDefault()), nextTriggerTime.get()));
    }
    /**
     * Get a formatted display of when this plugin will run next
     */
    public String getNextRunDisplay() {
        return getNextRunDisplay(System.currentTimeMillis());
    }

    /**
     * Get a formatted display of when this plugin will run next, including
     * condition information.
     * 
     * @param currentTimeMillis Current system time in milliseconds
     * @return Human-readable description of next run time or condition status
     */
    public String getNextRunDisplay(long currentTimeMillis) {
        if (!enabled) {
            return "Disabled";
        }

        // If plugin is running, show progress or status information
        if (isRunning()) {
            String prefixLabel = "Running";
            if(stopConditionManager.isPaused()){
                prefixLabel = "Paused";
            }
            
            if (!stopConditionManager.getConditions().isEmpty()) {
                double progressPct = getStopConditionProgress();
                if (progressPct > 0 && progressPct < 100) {
                    return String.format("%s (%.1f%% complete)", prefixLabel,progressPct);
                }
                return String.format("%s with conditions", prefixLabel);
            }
            return prefixLabel;
        }
        
        // Check for start conditions
        if (hasAnyStartConditions()) {
            // Check if we can determine the next trigger time
            Optional<ZonedDateTime> nextTrigger = getCurrentStartTriggerTime();
            if (nextTrigger.isPresent()) {
                ZonedDateTime triggerTime = nextTrigger.get();
                ZonedDateTime currentTime = ZonedDateTime.ofInstant(
                        Instant.ofEpochMilli(currentTimeMillis),
                        ZoneId.systemDefault());
                
                // If it's due to run now
                if (!currentTime.isBefore(triggerTime)) {
                    return "Due to run";
                }
                
                // Calculate time until next run
                Duration timeUntil = Duration.between(currentTime, triggerTime);
                long hours = timeUntil.toHours();
                long minutes = timeUntil.toMinutes() % 60;
                long seconds = timeUntil.getSeconds() % 60;
                
                if (hours > 0) {
                    return String.format("In %dh %dm", hours, minutes);
                } else if (minutes > 0) {
                    return String.format("In %dm %ds", minutes, seconds);
                } else {
                    return String.format("In %ds", seconds);
                }
            } else if (shouldStartImmediately()) {
                return "Due to run";
            } else if (hasTriggeredOneTimeStartConditions() && !canStartTriggerAgain()) {
                return "Completed";
            }
            
            return "Waiting for conditions";
        }
        
        
        
        return "Schedule not set";
    }
    
    /**
     * Adds a user-defined start condition to this plugin schedule entry.
     * Start conditions determine when the plugin should be executed.
     * 
     * @param condition The condition to add to the start conditions list
     */
    public void addStartCondition(Condition condition) {
        startConditionManager.addUserCondition(condition);
    }
    
    /**
     * Adds a user-defined stop condition to this plugin schedule entry.
     * Stop conditions determine when the plugin should terminate.
     * 
     * @param condition The condition to add to the stop conditions list
     */
    public void addStopCondition(Condition condition) {
        stopConditionManager.addUserCondition(condition);
    }

    /**
     * Returns all stop conditions configured for this plugin schedule entry.
     * 
     * @return List of currently active stop conditions
     */
    public List<Condition> getStopConditions() {
        return stopConditionManager.getConditions();
    }
    
    /**
     * Checks whether any stop conditions are defined for this plugin.
     * 
     * @return true if at least one stop condition exists, false otherwise
     */
    public boolean hasStopConditions() {
        return stopConditionManager.hasConditions();
    }
    
    /**
     * Checks whether any start conditions are defined for this plugin.
     * 
     * @return true if at least one start condition exists, false otherwise
     */
    public boolean hasStartConditions() {
        return startConditionManager.hasConditions();
    }
    
    /**
     * Returns all start conditions configured for this plugin schedule entry.
     * 
     * @return List of currently active start conditions
     */
    public List<Condition> getStartConditions() {
        return startConditionManager.getConditions();
    }

    /**
     * Determines if the plugin can be stopped based on its current state and conditions.
     * <p>
     * A plugin can be stopped if:
     * <ul>
     *   <li>It has finished its task</li>
     *   <li>It is running but has been disabled</li>
     *   <li>Plugin-defined stop conditions are met</li>
     * </ul>
     * 
     * @return true if the plugin can be stopped, false otherwise
     */
    public boolean allowedToBeStop() {
    
        if (isRunning()) {
            if (!isEnabled()){
                return true; //enabled was disabled -> stop the plugin gracefully -> soft stop should be trigged when possible
            }
        }
        // Check if conditions are met and we should stop when conditions are met
        if (arePluginStopConditionsMet() ) {
            return true;
        }

        return false;
    }
    public boolean shouldBeStopped() {    
        if (isRunning()) {
            if (!isEnabled()){
                return true; //enabled was disabled -> stop the plugin gracefully -> soft stop should be trigged when possible
            }
        }
        // Check if conditions are met and we should stop when conditions are met
        if (arePluginStopConditionsMet() && (areUserDefinedStopConditionsMet())) {
            if (stopConditionManager.getUserConditions().isEmpty()) {        
                 //* -> do not stop a plugin if there are no user defined stop conditions
                //* -> in that case the plugin must report finished to be stop or the user must manually stop it              
                return false; // we have plugin stop conditions -> we can stop the plugin -> stop condition of the plugin are look conditions, so if no condition are defined, we are allow to stop the plugin                
            }
            return true;
        }

        return false;
    }

    /**
     * Checks if plugin-defined stop conditions are met.
     * If no plugin conditions are defined, returns true to allow stopping.
     * 
     * @return true if plugin-defined stop conditions are met or none are defined
     */
    private boolean arePluginStopConditionsMet() {
        if (stopConditionManager.getPluginConditions().isEmpty()) {
            return true; // we have plugin stop conditions -> we can stop the plugin -> stop condition of the plugin are look conditions, so if no condition are defined, we are allow to stop the plugin
        }
        return stopConditionManager.arePluginConditionsMet();
    }

    /**
     * Checks if user-defined stop conditions are met.
     * These are conditions added through the UI rather than by the plugin itself. 
    
     * @return true if user-defined stop conditions are met, false if none exist or they're not met
     */
    private boolean areUserDefinedStopConditionsMet() {
        if (stopConditionManager.getUserConditions().isEmpty()) {
            return true;
        }
        return stopConditionManager.areUserConditionsMet();
    }

    /**
     * Checks if user-defined start conditions are met.
     * These are conditions added through the UI rather than by the plugin itself.
     * 
     * @return true if user-defined start conditions are met, false if none exist or they're not met
     */
    private boolean areUserStartConditionsMet() {
        if (startConditionManager.getUserConditions().isEmpty()) {
            return true;
        }
        return startConditionManager.areUserConditionsMet();
    }
    /**
     * Checks if plugin-defined start conditions are met.
     * If no plugin conditions are defined, returns true to allow starting.
     * 
     * @return true if plugin-defined start conditions are met or none are defined
     */
    private boolean arePluginStartConditionsMet() {
        if (startConditionManager.getPluginConditions().isEmpty()) {
            return true; // we have plugin start conditions -> we can start the plugin -> start condition of the plugin are look conditions, so if no condition are defined, we are allow to start the plugin
        }
        return startConditionManager.arePluginConditionsMet();
    }
    /**
     * Gets a description of the stop conditions for this plugin.
     * 
     * @return A string describing the stop conditions
     */
    public String getConditionsDescription() {
        return stopConditionManager.getDescription();
    }

    /**
     * Overloaded method that adds a custom message for the stop reason.
     * <p>
     * This method handles the graceful shutdown of a plugin by first attempting a soft
     * stop, with a custom message indicating why the plugin was stopped.
     *
     * @param successfulRun whether the plugin completed its task successfully
     * @param reason the enum reason why the plugin is being stopped
     * @param reasonMessage a custom message explaining why the plugin was stopped
     * @return true if stop was initiated, false otherwise
     */
    public boolean stop(boolean successfulRun, StopReason reason, String reasonMessage) {
        // Set the custom stop reason message
        if (!stopInitiated){
            this.lastStopReason = reasonMessage;
        }
        
        // Call the original stop method to handle the actual stopping logic
        return stop(successfulRun, reason);
    }
    /**
     * Initiates the stopping process for a plugin with appropriate monitoring.
     * <p>
     * This method handles the graceful shutdown of a plugin by first attempting a soft
     * stop, which allows the plugin to finish any critical operations. If the soft stop
     * fails after the configured timeout period, it may escalate to a hard stop for
     * plugins that support it.
     * <p>
     * The method also manages retry attempts for unresponsive plugins based on the
     * configured retry interval.
     *
     * @param successfulRun whether the plugin completed its task successfully
     * @return true if stop was initiated, false otherwise
     */
    public boolean stop(boolean successfulRun, StopReason reason) {
        ZonedDateTime now = ZonedDateTime.now();
        // Initial stop attempt
        if (allowedToBeStop() || reason == StopReason.HARD_STOP || reason == StopReason.PLUGIN_FINISHED ){
            if(!stopInitiated){
                if (stopInitiatedTime == null) {
                    stopInitiatedTime = now;
                }
                if (lastStopAttemptTime == null) {
                    lastStopAttemptTime = now;
                }
                this.setLastRunSuccessful(successfulRun);
                this.setLastStopReasonType(reason);
                this.onLastStopPluginConditionsSatisfied = arePluginStopConditionsMet();
                this.onLastStopUserConditionsSatisfied = areUserDefinedStopConditionsMet();
            }
            StringBuilder logMsg = new StringBuilder();
            logMsg.append("\n\tStopping the plugin \"").append(getCleanName()+"\"");
            String blockingStartMsg = startConditionManager.getBlockingExplanation();
            String blockingStopMsg = stopConditionManager.getBlockingExplanation();           
            if (reason != null) {
                logMsg.append("\n\t---current stop reason:").append("\n\t\t"+reason.toString());
                if (this.lastStopReason != null && !this.lastStopReason.isEmpty()) {
                    logMsg.append("\n\t---last stop reason:\n********\n").append(this.lastStopReason+ "\n********");
                }
            }
            logMsg.append("\n\t---is running: ").append(isRunning());
            logMsg.append("\n\t---plugin stop conditions satisfied: ").append(arePluginStopConditionsMet());
            logMsg.append("\n\t---user stop conditions satisfied: ").append(areUserDefinedStopConditionsMet());   
            log.info(logMsg.toString());
            logStopConditionsWithDetails();
            if (!stopInitiated  && reason != StopReason.HARD_STOP) {                                                                                            
                this.softStop(successfulRun); // This will start the monitoring thread
            }else if (reason == StopReason.HARD_STOP) {
                // If we are already stopping and the reason is hard stop, just log it                
                this.hardStop(successfulRun); // frist try soft stop, then hard stop if needed
            }          
        }else{
            StringBuilder logMsg = new StringBuilder();
            logMsg.append("\n\tPlugin ").append(name).append(" is not allowed to stop. ");
            String blockingStartMsg = startConditionManager.getBlockingExplanation();
            String blockingStopMsg = stopConditionManager.getBlockingExplanation();
            if (blockingStopMsg != null) {
                logMsg.append("\n\t -Blocking reason: ").append(blockingStopMsg);
            }            
            if (reason != null) {
                logMsg.append("\n\t -Current stop reason: ").append(reason.toString()).append(" -- ").append(this.lastStopReason);
            }
            logMsg.append("\n\t -is running: ").append(isRunning());
            logMsg.append("\n\t -plugin stop conditions: ").append(arePluginStopConditionsMet());
            logMsg.append("\n\t -user stop conditions: ").append(areUserDefinedStopConditionsMet());
            log.info(logMsg.toString());
        }
        log.info("\n\tPlugin {} stop initiated: {}", name, stopInitiated);
        return this.stopInitiated;
    }
    private void stop(boolean successfulRun) {
        ZonedDateTime now = ZonedDateTime.now();
      // Plugin didn't stop after previous attempts
        if (isRunning()) {            
            Duration timeSinceFirstAttempt = Duration.between(this.stopInitiatedTime, now);
            Duration timeSinceLastAttempt = Duration.between(this.lastStopAttemptTime, now);                
            // Force hard stop if we've waited too long
            if ( (hardStopTimeout.compareTo(Duration.ZERO) > 0 && timeSinceFirstAttempt.compareTo(hardStopTimeout) > 0) 
                && (getPlugin() instanceof SchedulablePlugin)
                && ((SchedulablePlugin) getPlugin()).allowHardStop()) {
                log.warn("Plugin {} failed to respond to soft stop after {} seconds - forcing hard stop", 
                        name, timeSinceFirstAttempt.toSeconds());
                
                // Stop current monitoring and start new one for hard stop
                stopMonitoringThread();
                this.setLastStopReasonType(StopReason.HARD_STOP);
                this.hardStop(successfulRun);
            }else if(getLastStopReasonType() == StopReason.HARD_STOP){ // Stop current monitoring and start new one for hard stop
                log.warn("Plugin {} user requested hard stop after {} seconds - forcing hard stop", 
                        name, timeSinceFirstAttempt.toSeconds());
                stopMonitoringThread();
                this.setLastStopReasonType(StopReason.HARD_STOP);
                this.hardStop(successfulRun);
            }
            // Retry soft stop at configured intervals
            else if (timeSinceLastAttempt.compareTo(softStopRetryInterval) > 0) {
                log.info("Plugin {} still running after soft stop - retrying (attempt time: {} seconds)", 
                        name, timeSinceFirstAttempt.toSeconds());
                lastStopAttemptTime = now;
                this.setLastStopReasonType(getLastStopReasonType());                
                this.softStop(successfulRun);
            }else if (hardStopTimeout.compareTo(Duration.ZERO) > 0  && timeSinceFirstAttempt.compareTo(hardStopTimeout.multipliedBy(3)) > 0) {                    
                log.error("Forcibly shutting down the client due to unresponsive plugin: {}", name);
                // Schedule client shutdown on the client thread to ensure it happens safely
                Microbot.getClientThread().invoke(() -> {
                    try {
                        // Log that we're shutting down
                        log.warn("Initiating emergency client shutdown due to plugin: {} cant be stopped", name);                        
                        // Give a short delay for logging to complete
                        Thread.sleep(1000);                        
                        // Forcibly exit the JVM with a non-zero status code to indicate abnormal termination
                        System.exit(1);
                    } catch (Exception e) {
                        log.error("Failed to shut down client", e);
                        // Ultimate fallback
                        Runtime.getRuntime().halt(1);
                    }
                    return true;
                });  
            }
        }
    }
    
    /**
     * Checks if the plugin should be stopped based on its conditions and performs a stop if needed.
     * <p>
     * This method evaluates both plugin-defined and user-defined stop conditions to determine
     * if the plugin should be stopped. If conditions indicate the plugin should stop, it initiates
     * the stop process.
     * <p>
     * It also handles resetting stop state if conditions no longer require the plugin to stop.
     *
     * @param successfulRun whether to mark this run as successful when stopping
     * @return true if stop process was initiated or is in progress, false otherwise
     */
    public boolean checkConditionsAndStop(boolean successfulRun) {        
        
        if (shouldBeStopped()) {
            this.stopInitiated = this.stop(successfulRun,StopReason.SCHEDULED_STOP);
            // Monitor thread will handle the successful stop case
        }
        // Reset stop tracking if conditions no longer require stopping
        else if (!isRunning() && stopInitiated) {
            log.info("Plugin {} conditions no longer require stopping - resetting stop state", name);
            this.stopInitiated = false;
            this.stopInitiatedTime = null;
            this.lastStopAttemptTime = null;
            stopMonitoringThread();
        }
        return this.stopInitiated;
        
    }

    /**
     * Logs all defined conditions when plugin starts
     */
    private void logStopConditions() {
        List<Condition> conditionList = stopConditionManager.getConditions();
        logConditionInfo(conditionList,"Defined Stop Conditions", true);
    }

    /**
     * Logs which conditions are met and which aren't when plugin stops
     */
    private void logStopConditionsWithDetails() {
        List<Condition> conditionList = stopConditionManager.getConditions();
        logConditionInfo(conditionList,"Defined Stop Conditions", true);
    }

    
    

    /**
     * Creates a consolidated log of all condition-related information
     * @param logINFOHeader The header to use for the log message
     * @param includeDetails Whether to include full details of conditions
     */
    public void logConditionInfo(List<Condition> conditionList, String logINFOHeader, boolean includeDetails) {
        
        StringBuilder sb = new StringBuilder();
        
        sb.append("\n\tPlugin '").append(cleanName).append("' [").append(logINFOHeader).append("]: ");

        if (conditionList.isEmpty()) {
            sb.append("\n\t\tNo stop conditions defined");
            log.info(sb.toString());
            return;
        }
        
        // Basic condition count and logic
        sb.append(" \n\t\t"+conditionList.size()+" condition(s) using ")
          .append(stopConditionManager.requiresAll() ? "AND" : "OR").append(" logic\n\t\t");
        
        if (!includeDetails) {
            log.info(sb.toString());
            return;
        }
        
        // Detailed condition listing with status
        
        int metCount = 0;
        
        for (int i = 0; i < conditionList.size(); i++) {
            Condition condition = conditionList.get(i);
            boolean isSatisfied = condition.isSatisfied();
            if (isSatisfied) metCount++;
            
            // Use the new getStatusInfo method for detailed status
            sb.append("  ").append(i + 1).append(". ")
              .append(condition.getStatusInfo(0, includeDetails).replace("\n", "\n\t\t    "));
            
            sb.append("\n\t\t");
        }
        
        if (includeDetails) {
            sb.append("Summary: ").append(metCount).append("/").append(conditionList.size())
              .append(" conditions met");
        }
        
        log.info(sb.toString());
    }



    

    // /**
    //  * Registers any custom stopping conditions provided by the plugin.
    //  * These conditions are combined with existing conditions using AND logic
    //  * to ensure plugin-defined conditions have the highest priority.
    //  * 
    //  * @param plugin    The plugin that might provide conditions
    //  * @param scheduled The scheduled instance managing the plugin
    //  */
    // public void registerPluginStoppingConditions() {
    //     if (this.plugin == null) {
    //         this.plugin = getPlugin();
    //     }
    //     log.info("Registering stopping conditions for plugin '{}'", name);
    //     if (this.plugin instanceof SchedulablePlugin) {
    //         SchedulablePlugin provider = (SchedulablePlugin) plugin;

    //         // Get conditions from the provider
            
    //         List<Condition> pluginConditions = provider.getStopCondition().getConditions();
    //         if (pluginConditions != null && !pluginConditions.isEmpty()) {                
    //             // Get or create plugin's logical structure
                
    //             LogicalCondition pluginLogic = provider.getStopCondition();                                
    //             // Set the new root condition                
    //             getStopConditionManager().setPluginCondition(pluginLogic);
                
    //             // Log with the consolidated method
    //             logStopConditionsWithDetails();
    //         } else {
    //             log.info("Plugin '{}' implements StoppingConditionProvider but provided no conditions",
    //                                     plugin.getName());
    //         }
    //     }
    // }
    // private boolean registerPluginStartingConditions(){
    //     if (this.plugin == null) {
    //         this.plugin = getPlugin();
    //     }
    //     log.info("Registering start conditions for plugin '{}'", name);
    //     if (this.plugin instanceof SchedulablePlugin) {
    //         SchedulablePlugin provider = (SchedulablePlugin) plugin;

    //         // Get conditions from the provider
    //         if (provider.getStartCondition() == null) {
    //             log.warn("Plugin '{}' implements ConditionProvider but provided no start conditions", plugin.getName());
    //             return false;
    //         }
    //         List<Condition> pluginConditions = provider.getStartCondition().getConditions();
    //         if (pluginConditions != null && !pluginConditions.isEmpty()) {
    //             // Create a new AND condition as the root

                

    //             // Get or create plugin's logical structure
    //             LogicalCondition pluginLogic = provider.getStartCondition();

    //             if (pluginLogic != null) {
    //                 for (Condition condition : pluginConditions) {
    //                     if(pluginLogic.contains(condition)){
    //                         continue;
    //                     }
    //                 }
                    
    //             }else{
    //                 throw new RuntimeException("Plugin '"+name+"' implements ConditionProvider but provided no conditions");
    //             }
                                
    //             // Set the new root condition
    //             getStartConditionManager().setPluginCondition(pluginLogic);
                
    //             // Log with the consolidated method
    //             logStartConditionsWithDetails();
    //         } else {
    //             log.info("Plugin '{}' implements condition Provider but provided no explicit start conditions defined",
    //                     plugin.getName());
    //         }
    //     }
    //     return true;

    // }
   
/**
     * Registers conditions from the plugin in an efficient manner.
     * This method uses the new updatePluginCondition approach to intelligently
     * merge conditions while preserving state and reducing unnecessary reinitializations.
     * 
     * @param updateMode Controls how conditions are merged (default: ADD_ONLY)
     */
    private void registerPluginConditions(UpdateOption updateOption) {
        if (this.plugin == null) {
            this.plugin = getPlugin();
        }
        
        log.info("Registering plugin conditions for plugin '{}' with update mode: {}", name, updateOption);
        
        
        // Register start conditions
        boolean startConditionsUpdated = registerPluginStartingConditions(updateOption);
        
        // Register stop conditions
        boolean stopConditionsUpdated = registerPluginStoppingConditions(updateOption);
        
        if (startConditionsUpdated || stopConditionsUpdated) {
            log.info("Successfully updated plugin conditions for '{}'", name);
            
            // Optimize structure if changes were made
            if (updateOption != UpdateOption.REMOVE_ONLY) {
                optimizeConditionStructures();
            }
        } else {
            log.debug("No changes needed to plugin conditions for '{}'", name);
        }
    }
    
    /**
     * Default version of registerPluginConditions that uses ADD_ONLY mode
     */
    private void registerPluginConditions() {
        registerPluginConditions(UpdateOption.SYNC);
    }

    /**
     * Registers or updates starting conditions from the plugin.
     * Uses the updatePluginCondition method to efficiently merge conditions.
     * 
     * @return true if conditions were updated, false if no changes were needed
     */
    private boolean registerPluginStartingConditions(UpdateOption updateOption) {
        if (this.plugin == null) {
            this.plugin = getPlugin();
        }
        
        log.debug("Registering start conditions for plugin '{}'", name);
        this.startConditionManager.pauseWatchdogs();
        this.startConditionManager.setPluginCondition(new OrCondition());
        if (!(this.plugin instanceof SchedulablePlugin)) {
            log.debug("Plugin '{}' is not a SchedulablePlugin, skipping start condition registration", name);
            return false;
        }
        
        SchedulablePlugin provider = (SchedulablePlugin) plugin;
        
        // Get conditions from the provider
        if (provider.getStartCondition() == null) {
            log.warn("Plugin '{}' implements ConditionProvider but provided no start conditions", plugin.getName());
            return false;
        }
        
        List<Condition> pluginConditions = provider.getStartCondition().getConditions();
        if (pluginConditions == null || pluginConditions.isEmpty()) {
            log.debug("Plugin '{}' provided no explicit start conditions", plugin.getName());
            return false;
        }
        
        // Get or create plugin's logical structure
        LogicalCondition pluginLogic = provider.getStartCondition();
        
        if (pluginLogic == null) {
            log.warn("Plugin '{}' returned null start condition", name);
            return false;
        }
        // Use the new update method with the specified option
        boolean updated = getStartConditionManager().updatePluginCondition(pluginLogic, updateOption);
    
        // Log with a consolidated method if changes were made
        if (updated) {
            log.debug("Updated start conditions for plugin '{}'", name);
            logStartConditionsWithDetails();
            
            // Validate the condition structure
            validateStartConditions();
        }
        this.startConditionManager.resumeWatchdogs();
        
        return updated;
    }

    /**
     * Registers or updates stopping conditions from the plugin.
     * Uses the updatePluginCondition method to efficiently merge conditions.
     * 
     * @return true if conditions were updated, false if no changes were needed
     */
    private boolean registerPluginStoppingConditions(UpdateOption updateOption) {
        if (this.plugin == null) {
            this.plugin = getPlugin();
        }
        this.stopConditionManager.pauseWatchdogs();
        this.stopConditionManager.setPluginCondition(new OrCondition());
        log.debug("Registering stopping conditions for plugin '{}'", name);
        
        if (!(this.plugin instanceof SchedulablePlugin)) {
            log.debug("Plugin '{}' is not a SchedulablePlugin, skipping stop condition registration", name);
            return false;
        }
        
        SchedulablePlugin provider = (SchedulablePlugin) plugin;
        
        // Get conditions from the provider
        if (provider.getStopCondition()  == null) {
            log.debug("Plugin '{}' provided no explicit stop conditions", plugin.getName());
            return false;
        }
        List<Condition> pluginConditions = provider.getStopCondition().getConditions();
        if (pluginConditions == null || pluginConditions.isEmpty()) {
            log.debug("Plugin '{}' provided no explicit stop conditions", plugin.getName());
            return false;
        }
        
        // Get plugin's logical structure
        LogicalCondition pluginLogic = provider.getStopCondition();
        
        if (pluginLogic == null) {
            log.warn("Plugin '{}' returned null stop condition", name);
            return false;
        }
        
        // Use the new update method with the specified option
        boolean updated = getStopConditionManager().updatePluginCondition(pluginLogic, updateOption);
    
        // Log with the consolidated method if changes were made
        if (updated) {
            log.debug("Updated stop conditions for plugin '{}'", name);
            logStopConditionsWithDetails();
            
            // Validate the condition structure
            validateStopConditions();
        }
        this.stopConditionManager.resumeWatchdogs();
        
        return updated;
    }
    
    /**
     * Creates and schedules watchdogs to monitor for condition changes from the plugin.
     * This allows plugins to dynamically update their conditions at runtime,
     * and have those changes automatically detected and integrated.
     * 
     * Both start and stop condition watchdogs are scheduled using the shared thread pool
     * from ConditionManager to avoid creating redundant resources.
     *
     * @param checkIntervalMillis How often to check for condition changes in milliseconds
     * @param updateMode Controls how conditions are merged during updates
     * @return true if at least one watchdog was successfully scheduled
     */
    public boolean scheduleConditionWatchdogs(long checkIntervalMillis, UpdateOption updateOption) {
        if(this.plugin == null) {
            this.plugin = getPlugin();
        }
        
        if (!watchdogsEnabled) {
            log.debug("Watchdogs are disabled for '{}', not scheduling", name);
            return false;
        }
        
        log.debug("\nScheduling condition watchdogs for plugin \n\t:'{}' with interval {}ms using update mode: {}", 
                 name, checkIntervalMillis, updateOption);
                 
        if (!(this.plugin instanceof SchedulablePlugin)) {            
            log.debug("Cannot schedule condition watchdogs for non-SchedulablePlugin");
            return false;                                        
        }
        
        // Cancel any existing watchdog tasks first
        //cancelConditionWatchdogs();
        
        SchedulablePlugin schedulablePlugin = (SchedulablePlugin) this.plugin;
        boolean anyScheduled = false;
        
        try {
            // Create suppliers that get the current plugin conditions
            Supplier<LogicalCondition> startConditionSupplier = 
                () -> schedulablePlugin.getStartCondition();
            
            Supplier<LogicalCondition> stopConditionSupplier = 
                () -> schedulablePlugin.getStopCondition();
            
            // Schedule the start condition watchdog
            startConditionWatchdogFuture = startConditionManager.scheduleConditionWatchdog(
                startConditionSupplier,
                checkIntervalMillis,
                updateOption
            );
            
            // Schedule the stop condition watchdog
            stopConditionWatchdogFuture = stopConditionManager.scheduleConditionWatchdog(
                stopConditionSupplier,
                checkIntervalMillis,
                updateOption
            );
            
            anyScheduled = true;
            log.debug("Scheduled condition watchdogs for plugin '{}' with interval {} ms using update mode: {}", 
                     name, checkIntervalMillis, updateOption);
        } catch (Exception e) {
            log.error("Failed to schedule condition watchdogs for '{}'", name, e);
        }
        
        return anyScheduled;
    }

    /**
     * Schedules condition watchdogs with the default ADD_ONLY update mode.
     * 
     * @param checkIntervalMillis How often to check for condition changes in milliseconds
     * @return true if at least one watchdog was successfully scheduled
     */
    public boolean scheduleConditionWatchdogs(long checkIntervalMillis) {
        return scheduleConditionWatchdogs(checkIntervalMillis, UpdateOption.SYNC);
    }
    
/**
     * Validates the start conditions structure and logs any issues found.
     * This helps identify potential problems with condition hierarchies.
     */
    private void validateStartConditions() {
        LogicalCondition startLogical = getStartConditionManager().getFullLogicalCondition();
        if (startLogical != null) {
            List<String> issues = startLogical.validateStructure();
            if (!issues.isEmpty()) {
                log.warn("Validation issues found in start conditions for '{}':", name);
                for (String issue : issues) {
                    log.warn("  - {}", issue);
                }
            }
        }
    }
       /**
     * Validates the stop conditions structure and logs any issues found.
     * This helps identify potential problems with condition hierarchies.
     */
    private void validateStopConditions() {
        LogicalCondition stopLogical = getStopConditionManager().getFullLogicalCondition();
        if (stopLogical != null) {
            List<String> issues = stopLogical.validateStructure();
            if (!issues.isEmpty()) {
                log.warn("Validation issues found in stop conditions for '{}':", name);
                for (String issue : issues) {
                    log.warn("  - {}", issue);
                }
            }
        }
    }
      /**
     * Optimizes both start and stop condition structures by flattening unnecessary nesting
     * and removing empty logical conditions.
     */
    private void optimizeConditionStructures() {
        // Optimize start conditions
        LogicalCondition startLogical = getStartConditionManager().getFullLogicalCondition();
        if (startLogical != null) {
            boolean optimized = startLogical.optimizeStructure();
            if (optimized) {
                log.debug("Optimized start condition structure for '{}'", name);
            }
        }
        
        // Optimize stop conditions
        LogicalCondition stopLogical = getStopConditionManager().getFullLogicalCondition();
        if (stopLogical != null) {
            boolean optimized = stopLogical.optimizeStructure();
            if (optimized) {
                log.debug("Optimized stop condition structure for '{}'", name);
            }
        }
    }
 
    
    /**
     * Checks if any condition watchdogs are currently active for this plugin.
     * 
     * @return true if at least one watchdog is active
     */
    public boolean hasActiveWatchdogs() {
        return (startConditionManager != null && startConditionManager.areWatchdogsRunning()) || 
               (stopConditionManager != null && stopConditionManager.areWatchdogsRunning());
    }
    
    /**
     * Properly clean up resources when this object is closed or disposed.
     * This is more reliable than using finalize() which is deprecated.
     */
    @Override
    public void close() {
        // Clean up watchdogs and other resources
        //cancelConditionWatchdogs();
        
        // Stop any monitoring threads
        stopMonitoringThread();
        
        // Ensure both condition managers are closed properly
        if (startConditionManager != null) {
            startConditionManager.close();
        }
        
        if (stopConditionManager != null) {
            stopConditionManager.close();
        }
        
        log.debug("Resources cleaned up for plugin schedule entry: '{}'", name);
    }
   
    /**
     * Calculates overall progress percentage across all conditions.
     * This respects the logical structure of conditions.
     * Returns 0 if progress cannot be determined.
     */
    public double getStopConditionProgress() {
        // If there are no conditions, no progress to report
        if (stopConditionManager == null || stopConditionManager.getConditions().isEmpty()) {
            return 0;
        }
        
        // If using logical root condition, respect its logical structure
        LogicalCondition rootLogical = stopConditionManager.getFullLogicalCondition();
        if (rootLogical != null) {
            return rootLogical.getProgressPercentage();
        }
        
        // Fallback for direct condition list: calculate based on AND/OR logic
        boolean requireAll = stopConditionManager.requiresAll();
        List<Condition> conditions = stopConditionManager.getConditions();
        
        if (requireAll) {
            // For AND logic, use the minimum progress (weakest link)
            return conditions.stream()
                .mapToDouble(Condition::getProgressPercentage)
                .min()
                .orElse(0.0);
        } else {
            // For OR logic, use the maximum progress (strongest link)
            return conditions.stream()
                .mapToDouble(Condition::getProgressPercentage)
                .max()
                .orElse(0.0);
        }
    }

    /**
     * Gets the total number of conditions being tracked.
     */
    public int getTotalStopConditionCount() {
        if (stopConditionManager == null) {
            return 0;
        }
        
        LogicalCondition rootLogical = stopConditionManager.getFullLogicalCondition();
        if (rootLogical != null) {
            return rootLogical.getTotalConditionCount();
        }
        
        return stopConditionManager.getConditions().stream()
            .mapToInt(Condition::getTotalConditionCount)
            .sum();
    }

    /**
     * Gets the number of conditions that are currently met.
     */
    public int getSatisfiedStopConditionCount() {
        if (stopConditionManager == null) {
            return 0;
        }
        
        LogicalCondition rootLogical = stopConditionManager.getFullLogicalCondition();
        if (rootLogical != null) {
            return rootLogical.getMetConditionCount();
        }
        
        return stopConditionManager.getConditions().stream()
            .mapToInt(Condition::getMetConditionCount)
            .sum();
    }
    public LogicalCondition getLogicalStopCondition() {
        return stopConditionManager.getFullLogicalCondition();
    }


    // Add getter/setter for the new fields
    public boolean isAllowRandomScheduling() {
        return allowRandomScheduling;
    }

    public void setAllowRandomScheduling(boolean allowRandomScheduling) {
        this.allowRandomScheduling = allowRandomScheduling;
    }

    public int getRunCount() {
        return runCount;
    }

    private void incrementRunCount() {
        this.runCount++;
    }

    // Setter methods for the configurable timeouts
    public void setSoftStopRetryInterval(Duration interval) {
        if (interval == null || interval.isNegative() || interval.isZero()) {
            return; // Invalid interval, do not set
        }
        if(interval.compareTo(Duration.ofSeconds(30)) < 0) {
            interval = Duration.ofSeconds(30); // Ensure minimum interval of 1 second
        }
        this.softStopRetryInterval = interval;
    }

    public void setHardStopTimeout(Duration timeout) {
        this.hardStopTimeout = timeout;
    }

 

    /**
     * Convert a list of ScheduledPlugin objects to JSON
     */
    public static String toJson(List<PluginScheduleEntry> plugins, String version) {
        return ScheduledSerializer.toJson(plugins, version);
    }


        /**
     * Parse JSON into a list of ScheduledPlugin objects
     */
    public static List<PluginScheduleEntry> fromJson(String json, String version) {
        return ScheduledSerializer.fromJson(json, version);
    }
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != getClass()) return false;
        
        PluginScheduleEntry that = (PluginScheduleEntry) o;
        
        // Two entries are equal if:
        // 1. They have the same name AND
        // 2. They have the same start conditions and stop conditions
        //    OR they are the same object reference
        
        if (!Objects.equals(name, that.name)) return false;
        
        // If they're the same name, we need to distinguish by conditions
        if (startConditionManager != null && that.startConditionManager != null) {
            if (!startConditionManager.getConditions().equals(that.startConditionManager.getConditions())) {
                return false;
            }
        } else if (startConditionManager != null || that.startConditionManager != null) {
            return false;
        }
        
        if (stopConditionManager != null && that.stopConditionManager != null) {
            return stopConditionManager.getConditions().equals(that.stopConditionManager.getConditions());
        } else {
            return stopConditionManager == null && that.stopConditionManager == null;
        }
    }

    @Override
    public int hashCode() {
        int result = name != null ? name.hashCode() : 0;
        result = 31 * result + (startConditionManager != null ? startConditionManager.getConditions().hashCode() : 0);
        result = 31 * result + (stopConditionManager != null ? stopConditionManager.getConditions().hashCode() : 0);
        return result;
    }

    public int getPriority() {
        return priority;
    }
    
    public void setPriority(int priority) {
        this.priority = priority;
    }
    
    public boolean isDefault() {
        return isDefault;
    }
    
    public void setDefault(boolean isDefault) {        
        this.isDefault = isDefault;
    }
    /**
    * Generic helper method to build condition diagnostics for both start and stop conditions
    * 
    * @param isStartCondition Whether to diagnose start conditions (true) or stop conditions (false)
    * @return A detailed diagnostic string
    */
    private String buildConditionDiagnostics(boolean isStartCondition) {
        StringBuilder sb = new StringBuilder();
        String conditionType = isStartCondition ? "Start" : "Stop";
        ConditionManager conditionManager = isStartCondition ? startConditionManager : stopConditionManager;
        List<Condition> conditions = isStartCondition ? getStartConditions() : getStopConditions();
        
        // Header with plugin name
        sb.append("[").append(cleanName).append("] ").append(conditionType).append(" condition diagnostics:\n");
        
        // Check if running (only relevant for start conditions)
        if (isStartCondition && isRunning()) {
            sb.append("- Plugin is already running (will not start again until stopped)\n");
            return sb.toString();
        }
        
        // Check for conditions
        if (conditions.isEmpty()) {
            sb.append("- No ").append(conditionType.toLowerCase()).append(" conditions defined\n");
            return sb.toString();
        }
        
        // Condition logic type
        sb.append("- Logic: ")
        .append(conditionManager.requiresAll() ? "ALL conditions must be met" : "ANY condition can be met")
        .append("\n");
        
        // Condition description
        sb.append("- Conditions: ")
        .append(conditionManager.getDescription())
        .append("\n");
        
        // Check if they can be fulfilled
        boolean canBeFulfilled = isStartCondition ? 
                hasFullfillableStartConditions() : 
                hasFullfillableStopConditions();
        
        if (!canBeFulfilled) {
            sb.append("- Conditions cannot be fulfilled (e.g., one-time conditions already triggered)\n");
        }
        
        // Progress
        double progress = isStartCondition ? 
                conditionManager.getProgressTowardNextTrigger() : 
                getStopConditionProgress();
        sb.append("- Progress: ")
        .append(String.format("%.1f%%", progress))
        .append("\n");
        
        // Next trigger time
        Optional<ZonedDateTime> nextTrigger = isStartCondition ? 
                getCurrentStartTriggerTime() : 
                getNextStopTriggerTime();
        
        sb.append("- Next trigger: ");
        if (nextTrigger.isPresent()) {
            ZonedDateTime now = ZonedDateTime.now(ZoneId.systemDefault());
            ZonedDateTime triggerTime = nextTrigger.get();
            
            sb.append(triggerTime).append("\n");
            sb.append("- Current time: ").append(now).append("\n");
            
            if (triggerTime.isBefore(now)) {
                sb.append("- Trigger time is in the past but conditions not met - may need reset\n");
            } else {
                Duration timeUntil = Duration.between(now, triggerTime);
                sb.append("- Time until trigger: ").append(formatDuration(timeUntil)).append("\n");
            }
        } else {
            sb.append("No future trigger time determined\n");
        }
        
        // Overall condition status
        boolean areConditionsMet = isStartCondition ? 
                startConditionManager.areAllConditionsMet() : 
                arePluginStopConditionsMet() && areUserDefinedStopConditionsMet();
        
        sb.append("- Status: ")
        .append(areConditionsMet ? 
                "CONDITIONS MET - Plugin is " + (isStartCondition ? "due to run" : "due to stop") : 
                "CONDITIONS NOT MET - Plugin " + (isStartCondition ? "will not run" : "will continue running"))
        .append("\n");
        
        // Individual condition status
        sb.append("- Individual conditions:\n");
        for (int i = 0; i < conditions.size(); i++) {
            Condition condition = conditions.get(i);
            sb.append("  ").append(i+1).append(". ")
            .append(condition.getDescription())
            .append(": ")
            .append(condition.isSatisfied() ? "SATISFIED" : "NOT SATISFIED");
            
            // Add progress if available
            double condProgress = condition.getProgressPercentage();
            if (condProgress > 0 && condProgress < 100) {
                sb.append(String.format(" (%.1f%%)", condProgress));
            }
            
            // For time conditions, show next trigger time
            if (condition instanceof TimeCondition) {
                Optional<ZonedDateTime> condTrigger = condition.getCurrentTriggerTime();
                if (condTrigger.isPresent()) {
                    sb.append(" (next trigger: ").append(condTrigger.get()).append(")");
                }
            }
            
            sb.append("\n");
        }
        
        return sb.toString();
    }

    /**
     * Performs a diagnostic check on start conditions and returns detailed information
     * about why a plugin might not be due to run
     * 
     * @return A string containing diagnostic information
     */
    public String diagnoseStartConditions() {
        return buildConditionDiagnostics(true);
    }

    /**
     * Performs a diagnostic check on stop conditions and returns detailed information
     * about why a plugin might or might not be due to stop
     * 
     * @return A string containing diagnostic information
     */
    public String diagnoseStopConditions() {
        return buildConditionDiagnostics(false);
    }

    /**
     * Formats a duration in a human-readable way
     */
    private String formatDuration(Duration duration) {
        long seconds = duration.getSeconds();
        if (seconds < 60) {
            return seconds + " seconds";
        } else if (seconds < 3600) {
            return String.format("%dm %ds", seconds / 60, seconds % 60);
        } else if (seconds < 86400) {
            return String.format("%dh %dm %ds", seconds / 3600, (seconds % 3600) / 60, seconds % 60);
        } else {
            return String.format("%dd %dh %dm", seconds / 86400, (seconds % 86400) / 3600, (seconds % 3600) / 60);
        }
    }

    /**
     * Checks whether this schedule entry contains only time-based conditions.
     * This is useful to determine if the plugin schedule is purely time-based
     * or if it has other types of conditions (e.g., resource, skill, etc.).
     *
     * @return true if the schedule only contains TimeCondition instances, false otherwise
     */
    public boolean hasOnlyTimeConditions() {
        // Check if start conditions contain only time conditions
        if (startConditionManager != null && !startConditionManager.hasOnlyTimeConditions()) {
            return false;
        }
        
        // Check if stop conditions contain only time conditions
        if (stopConditionManager != null && !stopConditionManager.hasOnlyTimeConditions()) {
            return false;
        }
        
        // Both condition managers contain only time conditions (or are empty)
        return true;
    }
    
    /**
     * Returns all non-time-based conditions from both start and stop conditions.
     * This can help identify which non-time conditions are present in the schedule.
     *
     * @return A list of all non-TimeCondition instances in this schedule entry
     */
    public List<Condition> getNonTimeConditions() {
        List<Condition> nonTimeConditions = new ArrayList<>();
        
        // Add non-time conditions from start conditions
        if (startConditionManager != null) {
            nonTimeConditions.addAll(startConditionManager.getNonTimeConditions());
        }
        
        // Add non-time conditions from stop conditions
        if (stopConditionManager != null) {
            nonTimeConditions.addAll(stopConditionManager.getNonTimeConditions());
        }
        
        return nonTimeConditions;
    }

    /**
     * Checks if this plugin would be due to run based only on its time conditions,
     * ignoring any non-time conditions that may be present in the schedule.
     * This is useful to determine if a plugin is being blocked from running by
     * time conditions or by other types of conditions.
     * 
     * @return true if the plugin would be scheduled to run based solely on time conditions
     */
    public boolean wouldRunBasedOnTimeConditionsOnly() {
        // Check if we're already running
        if (isRunning()) {
            return false;
        }
        
        // If no start conditions defined, plugin can't run automatically
        if (!hasAnyStartConditions()) {
            return false;
        }
        
        // Check if time conditions alone would be satisfied
        return startConditionManager.wouldBeTimeOnlySatisfied();
    }
    
    /**
     * Provides detailed diagnostic information about why a plugin is or isn't
     * running based on its time conditions only.
     * 
     * @return A diagnostic string explaining the time condition status
     */
    public String diagnoseTimeConditionScheduling() {
        StringBuilder sb = new StringBuilder();
        sb.append("Time condition scheduling diagnosis for '").append(cleanName).append("':\n");
        
        // First check if plugin is already running
        if (isRunning()) {
            sb.append("Plugin is already running - will not be scheduled again until stopped.\n");
            return sb.toString();
        }
        
        // Check if there are any start conditions
        if (!hasAnyStartConditions()) {
            sb.append("No start conditions defined - plugin can't be automatically scheduled.\n");
            return sb.toString();
        }
        
        // Get time-only condition status
        boolean wouldRunOnTimeOnly = startConditionManager.wouldBeTimeOnlySatisfied();
        boolean allConditionsMet = startConditionManager.areAllConditionsMet();
        
        sb.append("Time conditions only: ").append(wouldRunOnTimeOnly ? "WOULD RUN" : "WOULD NOT RUN").append("\n");
        sb.append("All conditions: ").append(allConditionsMet ? "SATISFIED" : "NOT SATISFIED").append("\n");
        
        // If time conditions would run but all conditions wouldn't, non-time conditions are blocking
        if (wouldRunOnTimeOnly && !allConditionsMet) {
            sb.append("Plugin is being blocked by non-time conditions.\n");
            
            // List the non-time conditions that are not satisfied
            List<Condition> nonTimeConditions = startConditionManager.getNonTimeConditions();
            sb.append("Non-time conditions blocking execution:\n");
            
            for (Condition condition : nonTimeConditions) {
                if (!condition.isSatisfied()) {
                    sb.append("  - ").append(condition.getDescription())
                      .append(" (").append(condition.getType()).append(")\n");
                }
            }
        } 
        // If time conditions would not run, show time condition status
        else if (!wouldRunOnTimeOnly) {
            sb.append("Plugin is waiting for time conditions to be met.\n");
            
            // Show next trigger time if available
            Optional<ZonedDateTime> nextTrigger = startConditionManager.getCurrentTriggerTime();
            if (nextTrigger.isPresent()) {
                ZonedDateTime now = ZonedDateTime.now(ZoneId.systemDefault());
                Duration until = Duration.between(now, nextTrigger.get());
                
                sb.append("Next time trigger at: ").append(nextTrigger.get())
                  .append(" (").append(formatDuration(until)).append(" from now)\n");
            } else {
                sb.append("No future time trigger determined.\n");
            }
        }
        
        // Add detailed time condition diagnosis from condition manager
        sb.append("\n").append(startConditionManager.diagnoseTimeConditionsSatisfaction());
        
        return sb.toString();
    }
    
    /**
     * Creates a modified version of this schedule entry that contains only time conditions.
     * This is useful for evaluating how the plugin would be scheduled if only time 
     * conditions were considered.
     * 
     * @return A new PluginScheduleEntry with the same configuration but only time conditions
     */
    public PluginScheduleEntry createTimeOnlySchedule() {
        // Create a new schedule entry with the same basic properties
        PluginScheduleEntry timeOnlyEntry = new PluginScheduleEntry(
            name, 
            mainTimeStartCondition != null ? mainTimeStartCondition : null, 
            enabled,
            allowRandomScheduling
        );
        
        // Create time-only condition managers
        if (startConditionManager != null) {
            ConditionManager timeOnlyStartManager = startConditionManager.createTimeOnlyConditionManager();
            timeOnlyEntry.startConditionManager.setUserLogicalCondition(
                timeOnlyStartManager.getUserLogicalCondition());
            timeOnlyEntry.startConditionManager.setPluginCondition(
                timeOnlyStartManager.getPluginCondition());
        }
        
        if (stopConditionManager != null) {
            ConditionManager timeOnlyStopManager = stopConditionManager.createTimeOnlyConditionManager();
            timeOnlyEntry.stopConditionManager.setUserLogicalCondition(
                timeOnlyStopManager.getUserLogicalCondition());
            timeOnlyEntry.stopConditionManager.setPluginCondition(
                timeOnlyStopManager.getPluginCondition());
        }
        
        return timeOnlyEntry;
    }

    /**
     * Flag to track whether this plugin entry is currently paused
     */
    private boolean paused = false;
    
    /**
     * Pauses all time conditions in both stop and start condition managers.
     * When paused, time conditions cannot be satisfied and their trigger times
     * will be shifted when resumed.
     * 
     * @return true if successfully paused, false if already paused
     */
    public boolean pause() {
        if (paused) {
            return false; // Already paused
        }
        
        // Pause both condition managers
        if (stopConditionManager != null) {
            stopConditionManager.pause();
        }
        
        if (startConditionManager != null) {
            startConditionManager.pause();
        }
        
        paused = true;
        log.debug("Paused time conditions for plugin: {}", name);
        return true;
    }
    
    /**
     * resumes all time conditions in both stop and start condition managers.
     * When resumed, time conditions will resume with their trigger times shifted
     * by the duration of the pause.
     * 
     * @return true if successfully resumed, false if not currently paused
     */
    public boolean resume() {
        if (!paused) {
            return false; // Not paused
        }
        // resume both condition managers
        if (stopConditionManager != null) {
            stopConditionManager.resume();
        }
        
        if (startConditionManager != null) {
            startConditionManager.resume();
        }        
        paused = false;
        return true;
    }
    
    /**
     * Checks if this plugin entry is currently paused.
     * 
     * @return true if paused, false otherwise
     */
    public boolean isPaused() {
        return paused;
    }

    /**
     * Gets the estimated time until start conditions will be satisfied.
     * This method uses the new estimation system to provide more accurate
     * predictions for when the plugin can start running.
     * 
     * @return Optional containing the estimated duration until start conditions are satisfied
     */
    public Optional<Duration> getEstimatedStartTimeWhenIsSatisfied() {
        if (!enabled) {
            return Optional.empty();
        }
        
        if (startConditionManager == null) {
            // No start conditions means plugin can start immediately
            return Optional.of(Duration.ZERO);
        }
        
        return startConditionManager.getEstimatedDurationUntilSatisfied();
    }
    
    /**
     * Gets the estimated time until start conditions will be satisfied, considering only user-defined conditions.
     * This method focuses only on user-configurable start conditions.
     * 
     * @return Optional containing the estimated duration until user start conditions are satisfied
     */
    public Optional<Duration> getEstimatedStartTimeWhenIsSatisfiedUserBased() {
        if (!enabled) {
            return Optional.empty();
        }
        
        if (startConditionManager == null) {
            return Optional.of(Duration.ZERO);
        }
        
        return startConditionManager.getEstimatedDurationUntilUserConditionsSatisfied();
    }
    
    /**
     * Gets the estimated time until stop conditions will be satisfied.
     * This method uses only user-defined stop conditions to predict when the plugin
     * should stop based on user configuration.
     * 
     * @return Optional containing the estimated duration until stop conditions are satisfied
     */
    public Optional<Duration> getEstimatedStopTimeWhenIsSatisfied() {
        if (stopConditionManager == null) {
            // No stop conditions means plugin will run indefinitely
            return Optional.empty();
        }
        
        return stopConditionManager.getEstimatedDurationUntilUserConditionsSatisfied();
    }
    
    /**
     * Gets a formatted string representation of the estimated start time.
     * 
     * @return A human-readable string describing when the plugin is estimated to start
     */
    public String getEstimatedStartTimeDisplay() {
        Optional<Duration> estimate = getEstimatedStartTimeWhenIsSatisfied();
        if (estimate.isPresent()) {
            return formatEstimatedDuration(estimate.get(), "start");
        }
        return "Cannot estimate start time";
    }
    
    /**
     * Gets a formatted string representation of the estimated stop time.
     * 
     * @return A human-readable string describing when the plugin is estimated to stop
     */
    public String getEstimatedStopTimeDisplay() {
        Optional<Duration> estimate = getEstimatedStopTimeWhenIsSatisfied();
        if (estimate.isPresent()) {
            return formatEstimatedDuration(estimate.get(), "stop");
        }
        return "No stop conditions or cannot estimate";
    }
    
    /**
     * Helper method to format estimated durations into human-readable strings.
     * 
     * @param duration The duration to format
     * @param action The action description ("start" or "stop")
     * @return A formatted string representation
     */
    private String formatEstimatedDuration(Duration duration, String action) {
        long seconds = duration.getSeconds();
        
        if (seconds <= 0) {
            return "Ready to " + action + " now";
        } else if (seconds < 60) {
            return String.format("Estimated to %s in ~%d seconds", action, seconds);
        } else if (seconds < 3600) {
            return String.format("Estimated to %s in ~%d minutes", action, seconds / 60);
        } else if (seconds < 86400) {
            return String.format("Estimated to %s in ~%d hours", action, seconds / 3600);
        } else {
            long days = seconds / 86400;
            return String.format("Estimated to %s in ~%d days", action, days);
        }
    }
}
