/*
 * Copyright (C) 2012 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.android.tools.sdkcontroller.handlers;

import java.util.ArrayList;
import java.util.List;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Message;
import android.os.SystemClock;
import android.util.Log;

import com.android.tools.sdkcontroller.lib.EmulatorConnection;


public class SensorsHandler extends BaseHandler {

    @SuppressWarnings("hiding")
    private static String TAG = SensorsHandler.class.getSimpleName();
    @SuppressWarnings("hiding")
    private static boolean DEBUG = true;
    private static boolean VERBOSE_TIMING = false;

    /**
     * Sensor "enabled by emulator" state has changed.
     * Parameter {@code obj} is the {@link MonitoredSensor}.
     */
    public static final int SENSOR_STATE_CHANGED = 1;
    /**
     * Sensor display value has changed.
     * Parameter {@code obj} is the {@link MonitoredSensor}.
     */
    public static final int SENSOR_DISPLAY_MODIFIED = 2;

    /** Array containing monitored sensors. */
    private final List<MonitoredSensor> mSensors = new ArrayList<MonitoredSensor>();
    private SensorManager mSenMan;

    public SensorsHandler() {
        super(HandlerType.Sensor, EmulatorConnection.SENSORS_PORT);
    }

    /**
     * Returns the list of sensors found on the device.
     * The list is computed once by {@link #onStart(EmulatorConnection, Context)}.
     *
     * @return A non-null possibly-empty list of sensors.
     */
    public List<MonitoredSensor> getSensors() {
        return mSensors;
    }

    @Override
    public void onStart(EmulatorConnection connection, Context context) {
        super.onStart(connection, context);

        // Iterate through the available sensors, adding them to the array.
        SensorManager sm = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
        mSenMan = sm;
        List<Sensor> sensors = sm.getSensorList(Sensor.TYPE_ALL);
        int cur_index = 0;
        for (int n = 0; n < sensors.size(); n++) {
            Sensor avail_sensor = sensors.get(n);

            // There can be multiple sensors of the same type. We need only one.
            if (!isSensorTypeAlreadyMonitored(avail_sensor.getType())) {
                // The first sensor we've got for the given type is not
                // necessarily the right one. So, use the default sensor
                // for the given type.
                Sensor def_sens = sm.getDefaultSensor(avail_sensor.getType());
                MonitoredSensor to_add = new MonitoredSensor(def_sens);
                cur_index++;
                mSensors.add(to_add);
                if (DEBUG) Log.d(TAG, String.format(
                        "Monitoring sensor #%02d: Name = '%s', Type = 0x%x",
                        cur_index, def_sens.getName(), def_sens.getType()));
            }
        }
    }

    @Override
    public void onStop() {
        stopSensors();
        super.onStop();
    }

    /**
     * Called when a query is received from the emulator. NOTE: This method is
     * called from the I/O loop.
     *
     * @param query Name of the query received from the emulator. The allowed
     *            queries are: 'list' - Lists sensors that are monitored by this
     *            application. The application replies to this command with a
     *            string: 'List:<name1>\n<name2>\n...<nameN>\n\0" 'start' -
     *            Starts monitoring sensors. There is no reply for this command.
     *            'stop' - Stops monitoring sensors. There is no reply for this
     *            command. 'enable:<sensor|all> - Enables notifications for a
     *            sensor / all sensors. 'disable:<sensor|all> - Disables
     *            notifications for a sensor / all sensors.
     * @param param Query parameters.
     * @return Zero-terminated reply string. String must be formatted as such:
     *         "ok|ko[:reply data]"
     */
    @Override
    public String onEmulatorQuery(String query, String param) {
        if (query.contentEquals("list")) {
            return onQueryList();
        } else if (query.contentEquals("start")) {
            return onQueryStart();
        } else if (query.contentEquals("stop")) {
            return onQueryStop();
        } else if (query.contentEquals("enable")) {
            return onQueryEnable(param);
        } else if (query.contentEquals("disable")) {
            return onQueryDisable(param);
        } else {
            Log.e(TAG, "Unknown query " + query + "(" + param + ")");
            return "ko:Query is unknown\0";
        }
    }

    /**
     * Called when a BLOB query is received from the emulator. NOTE: This method
     * is called from the I/O loop, so all communication with the emulator will
     * be "on hold" until this method returns.
     *
     * @param array contains BLOB data for the query.
     * @return Zero-terminated reply string. String must be formatted as such:
     *         "ok|ko[:reply data]"
     */
    @Override
    public String onEmulatorBlobQuery(byte[] array) {
        return "ko:Unexpected\0";
    }

    /***************************************************************************
     * Query handlers
     **************************************************************************/

    /**
     * Handles 'list' query.
     *
     * @return List of emulator-friendly names for sensors that are available on
     *         the device.
     */
    private String onQueryList() {
        // List monitored sensors.
        String list = "ok:";
        for (MonitoredSensor sensor : mSensors) {
            list += sensor.getEmulatorFriendlyName();
            list += "\n";
        }
        list += '\0'; // Response must end with zero-terminator.
        return list;
    }

    /**
     * Handles 'start' query.
     *
     * @return Empty string. This is a "command" query that doesn't assume any
     *         response.
     */
    private String onQueryStart() {
        startSensors();
        return "ok\0";
    }

    /**
     * Handles 'stop' query.
     *
     * @return Empty string. This is a "command" query that doesn't assume any
     *         response.
     */
    private String onQueryStop() {
        stopSensors();
        return "ok\0";
    }

    /**
     * Handles 'enable' query.
     *
     * @param param Sensor selector: - all Enables all available sensors, or -
     *            <name> Emulator-friendly name of a sensor to enable.
     * @return "ok" / "ko": success / failure.
     */
    private String onQueryEnable(String param) {
        if (param.contentEquals("all")) {
            // Enable all sensors.
            for (MonitoredSensor sensor : mSensors) {
                sensor.enableSensor();
            }
            return "ok\0";
        }

        // Lookup sensor by emulator-friendly name.
        MonitoredSensor sensor = getSensorByEFN(param);
        if (sensor != null) {
            sensor.enableSensor();
            return "ok\0";
        } else {
            return "ko:Sensor not found\0";
        }
    }

    /**
     * Handles 'disable' query.
     *
     * @param param Sensor selector: - all Disables all available sensors, or -
     *            <name> Emulator-friendly name of a sensor to disable.
     * @return "ok" / "ko": success / failure.
     */
    private String onQueryDisable(String param) {
        if (param.contentEquals("all")) {
            // Disable all sensors.
            for (MonitoredSensor sensor : mSensors) {
                sensor.disableSensor();
            }
            return "ok\0";
        }

        // Lookup sensor by emulator-friendly name.
        MonitoredSensor sensor = getSensorByEFN(param);
        if (sensor != null) {
            sensor.disableSensor();
            return "ok\0";
        } else {
            return "ko:Sensor not found\0";
        }
    }

    /***************************************************************************
     * Internals
     **************************************************************************/

    /**
     * Start listening to all monitored sensors.
     */
    private void startSensors() {
        for (MonitoredSensor sensor : mSensors) {
            sensor.startListening();
        }
    }

    /**
     * Stop listening to all monitored sensors.
     */
    private void stopSensors() {
        for (MonitoredSensor sensor : mSensors) {
            sensor.stopListening();
        }
    }

    /**
     * Checks if a sensor for the given type is already monitored.
     *
     * @param type Sensor type (one of the Sensor.TYPE_XXX constants)
     * @return true if a sensor for the given type is already monitored, or
     *         false if the sensor is not monitored.
     */
    private boolean isSensorTypeAlreadyMonitored(int type) {
        for (MonitoredSensor sensor : mSensors) {
            if (sensor.getType() == type) {
                return true;
            }
        }
        return false;
    }

    /**
     * Looks up a monitored sensor by its emulator-friendly name.
     *
     * @param name Emulator-friendly name to look up the monitored sensor for.
     * @return Monitored sensor for the fiven name, or null if sensor was not
     *         found.
     */
    private MonitoredSensor getSensorByEFN(String name) {
        for (MonitoredSensor sensor : mSensors) {
            if (sensor.mEmulatorFriendlyName.contentEquals(name)) {
                return sensor;
            }
        }
        return null;
    }

    /**
     * Encapsulates a sensor that is being monitored. To monitor sensor changes
     * each monitored sensor registers with sensor manager as a sensor listener.
     * To control sensor monitoring from the UI, each monitored sensor has two
     * UI controls associated with it: - A check box (named after sensor) that
     * can be used to enable, or disable listening to the sensor changes. - A
     * text view where current sensor value is displayed.
     */
    public class MonitoredSensor {
        /** Sensor to monitor. */
        private final Sensor mSensor;
        /** The sensor name to display in the UI. */
        private String mUiName = "";
        /** Text view displaying the value of the sensor. */
        private String mValue = null;
        /** Emulator-friendly name for the sensor. */
        private String mEmulatorFriendlyName;
        /** Formats string to show in the TextView. */
        private String mTextFmt;
        /** Formats string to send to the emulator. */
        private String mMsgFmt;
        private int mNbValues = 0;
        private float[] mValues = new float[3];
        /**
         * Enabled state. This state is controlled by the emulator, that
         * maintains its own list of sensors. So, if a sensor is missing, or is
         * disabled in the emulator, it should be disabled in this application.
         */
        private boolean mEnabledByEmulator = false;
        /** User-controlled enabled state. */
        private boolean mEnabledByUser = true;
        private final OurSensorEventListener mListener = new OurSensorEventListener();

        /**
         * Constructs MonitoredSensor instance, and register the listeners.
         *
         * @param sensor Sensor to monitor.
         */
        MonitoredSensor(Sensor sensor) {
            mSensor = sensor;
            mEnabledByUser = true;

            // Set appropriate sensor name depending on the type. Unfortunately,
            // we can't really use sensor.getName() here, since the value it
            // returns (although resembles the purpose) is a bit vaguer than it
            // should be. Also choose an appropriate format for the strings that
            // display sensor's value, and strings that are sent to the
            // emulator.
            switch (sensor.getType()) {
                case Sensor.TYPE_ACCELEROMETER:
                    mUiName = "Accelerometer";
                    // 3 floats.
                    mTextFmt = "%+.2f %+.2f %+.2f";
                    mEmulatorFriendlyName = "acceleration";
                    mMsgFmt = mEmulatorFriendlyName + ":%g:%g:%g\0";
                    break;
                case 9: // Sensor.TYPE_GRAVITY is missing in API 7
                    // 3 floats.
                    mUiName = "Gravity";
                    mTextFmt = "%+.2f %+.2f %+.2f";
                    mEmulatorFriendlyName = "gravity";
                    mMsgFmt = mEmulatorFriendlyName + ":%g:%g:%g\0";
                    break;
                case Sensor.TYPE_GYROSCOPE:
                    mUiName = "Gyroscope";
                    // 3 floats.
                    mTextFmt = "%+.2f %+.2f %+.2f";
                    mEmulatorFriendlyName = "gyroscope";
                    mMsgFmt = mEmulatorFriendlyName + ":%g:%g:%g\0";
                    break;
                case Sensor.TYPE_LIGHT:
                    mUiName = "Light";
                    // 1 integer.
                    mTextFmt = "%.0f";
                    mEmulatorFriendlyName = "light";
                    mMsgFmt = mEmulatorFriendlyName + ":%g\0";
                    break;
                case 10: // Sensor.TYPE_LINEAR_ACCELERATION is missing in API 7
                    mUiName = "Linear acceleration";
                    // 3 floats.
                    mTextFmt = "%+.2f %+.2f %+.2f";
                    mEmulatorFriendlyName = "linear-acceleration";
                    mMsgFmt = mEmulatorFriendlyName + ":%g:%g:%g\0";
                    break;
                case Sensor.TYPE_MAGNETIC_FIELD:
                    mUiName = "Magnetic field";
                    // 3 floats.
                    mTextFmt = "%+.2f %+.2f %+.2f";
                    mEmulatorFriendlyName = "magnetic-field";
                    mMsgFmt = mEmulatorFriendlyName + ":%g:%g:%g\0";
                    break;
                case Sensor.TYPE_ORIENTATION:
                    mUiName = "Orientation";
                    // 3 integers.
                    mTextFmt = "%+03.0f %+03.0f %+03.0f";
                    mEmulatorFriendlyName = "orientation";
                    mMsgFmt = mEmulatorFriendlyName + ":%g:%g:%g\0";
                    break;
                case Sensor.TYPE_PRESSURE:
                    mUiName = "Pressure";
                    // 1 integer.
                    mTextFmt = "%.0f";
                    mEmulatorFriendlyName = "pressure";
                    mMsgFmt = mEmulatorFriendlyName + ":%g\0";
                    break;
                case Sensor.TYPE_PROXIMITY:
                    mUiName = "Proximity";
                    // 1 integer.
                    mTextFmt = "%.0f";
                    mEmulatorFriendlyName = "proximity";
                    mMsgFmt = mEmulatorFriendlyName + ":%g\0";
                    break;
                case 11: // Sensor.TYPE_ROTATION_VECTOR is missing in API 7
                    mUiName = "Rotation";
                    // 3 floats.
                    mTextFmt = "%+.2f %+.2f %+.2f";
                    mEmulatorFriendlyName = "rotation";
                    mMsgFmt = mEmulatorFriendlyName + ":%g:%g:%g\0";
                    break;
                case Sensor.TYPE_TEMPERATURE:
                    mUiName = "Temperature";
                    // 1 integer.
                    mTextFmt = "%.0f";
                    mEmulatorFriendlyName = "tempterature";
                    mMsgFmt = mEmulatorFriendlyName + ":%g\0";
                    break;
                default:
                    mUiName = "<Unknown>";
                    mTextFmt = "N/A";
                    mEmulatorFriendlyName = "unknown";
                    mMsgFmt = mEmulatorFriendlyName + "\0";
                    if (DEBUG) Log.e(TAG, "Unknown sensor type " + mSensor.getType() +
                            " for sensor " + mSensor.getName());
                    break;
            }
        }

        public String getUiName() {
            return mUiName;
        }

        public String getValue() {
            String val = mValue;

            if (val == null) {
                int len = mNbValues;
                float[] values = mValues;
                if (len == 3) {
                    val = String.format(mTextFmt, values[0], values[1],values[2]);
                } else if (len == 2) {
                    val = String.format(mTextFmt, values[0], values[1]);
                } else if (len == 1) {
                    val = String.format(mTextFmt, values[0]);
                }
                mValue = val;
            }

            return val == null ? "??" : val;
        }

        public boolean isEnabledByEmulator() {
            return mEnabledByEmulator;
        }

        public boolean isEnabledByUser() {
            return mEnabledByUser;
        }

        /**
         * Handles checked state change for the associated CheckBox. If check
         * box is checked we will register sensor change listener. If it is
         * unchecked, we will unregister sensor change listener.
         */
        public void onCheckedChanged(boolean isChecked) {
            mEnabledByUser = isChecked;
            if (isChecked) {
                startListening();
            } else {
                stopListening();
            }
        }

        // ---------

        /**
         * Gets sensor type.
         *
         * @return Sensor type as one of the Sensor.TYPE_XXX constants.
         */
        private int getType() {
            return mSensor.getType();
        }

        /**
         * Gets sensor's emulator-friendly name.
         *
         * @return Sensor's emulator-friendly name.
         */
        private String getEmulatorFriendlyName() {
            return mEmulatorFriendlyName;
        }

        /**
         * Starts monitoring the sensor. NOTE: This method is called from
         * outside of the UI thread.
         */
        private void startListening() {
            if (mEnabledByEmulator && mEnabledByUser) {
                if (DEBUG) Log.d(TAG, "+++ Sensor " + getEmulatorFriendlyName() + " is started.");
                mSenMan.registerListener(mListener, mSensor, SensorManager.SENSOR_DELAY_NORMAL);
            }
        }

        /**
         * Stops monitoring the sensor. NOTE: This method is called from outside
         * of the UI thread.
         */
        private void stopListening() {
            if (DEBUG) Log.d(TAG, "--- Sensor " + getEmulatorFriendlyName() + " is stopped.");
            mSenMan.unregisterListener(mListener);
        }

        /**
         * Enables sensor events. NOTE: This method is called from outside of
         * the UI thread.
         */
        private void enableSensor() {
            if (DEBUG) Log.d(TAG, ">>> Sensor " + getEmulatorFriendlyName() + " is enabled.");
            mEnabledByEmulator = true;
            mNbValues = 0;
            mValue = null;

            Message msg = Message.obtain();
            msg.what = SENSOR_STATE_CHANGED;
            msg.obj = MonitoredSensor.this;
            notifyUiHandlers(msg);
        }

        /**
         * Disables sensor events. NOTE: This method is called from outside of
         * the UI thread.
         */
        private void disableSensor() {
            if (DEBUG) Log.w(TAG, "<<< Sensor " + getEmulatorFriendlyName() + " is disabled.");
            mEnabledByEmulator = false;
            mValue = "Disabled by emulator";

            Message msg = Message.obtain();
            msg.what = SENSOR_STATE_CHANGED;
            msg.obj = MonitoredSensor.this;
            notifyUiHandlers(msg);
        }

        private class OurSensorEventListener implements SensorEventListener {
            /**
             * Handles "sensor changed" event. This is an implementation of the
             * SensorEventListener interface.
             */
            @Override
            public void onSensorChanged(SensorEvent event) {
                mSensorCount++;
                long now = SystemClock.currentThreadTimeMillis(); //.elapsedRealtime();

                // Display current sensor value, and format message that will be
                // sent to the emulator.
                float[] values = event.values;
                final int len = values.length;
                String str;
                if (len == 3) {
                    str = String.format(mMsgFmt, values[0], values[1], values[2]);
                } else if (len == 2) {
                    str = String.format(mMsgFmt, values[0], values[1]);
                } else if (len == 1) {
                    str = String.format(mMsgFmt, values[0]);
                } else {
                    Log.e(TAG, "Unexpected number of values " + len
                            + " in onSensorChanged for sensor " + mSensor.getName());
                    return;
                }
                sendEventToEmulator(str);

                long now1 = SystemClock.currentThreadTimeMillis();

                if (hasUiHandler()) {
                    // TODO reduce the UI update rate. 2~4 fps would be just good enough.
                    mNbValues = len;
                    mValues[0] = values[0];
                    if (len > 1) {
                        mValues[1] = values[1];
                        if (len > 2) {
                            mValues[2] = values[2];
                        }
                    }
                    mValue = null;

                    Message msg = Message.obtain();
                    msg.what = SENSOR_DISPLAY_MODIFIED;
                    msg.obj = MonitoredSensor.this;
                    notifyUiHandlers(msg);
                }

                if (VERBOSE_TIMING) {
                    // Computes average update time for this sensor or globally.
                    // Also computes the average time spend formatting sensor values.
                    // average call times between "now"
                    if (mGlobalLastNowTS != 0) {
                        long nowDiff = now - mGlobalLastNowTS;
                        mGlobalLastNowTS = now;
                        if (mGlobalAvgNowMs != 0) {
                            mGlobalAvgNowMs = (mGlobalAvgNowMs + nowDiff) / 2;
                        } else {
                            mGlobalAvgNowMs = nowDiff;
                        }
                    } else {
                        mGlobalLastNowTS = now;
                    }

                    if (mLastNowTS != 0) {
                        long nowDiff = now - mLastNowTS;
                        mLastNowTS = now;
                        if (mAvgNowMs != 0) {
                            mAvgNowMs = (mAvgNowMs + nowDiff) / 2;
                        } else {
                            mAvgNowMs = nowDiff;
                        }

                        // average now to now1 time (time to format string)
                        nowDiff = now1 - now;
                        if (mAvgNow1Ms != 0) {
                            mAvgNow1Ms = (mAvgNow1Ms + nowDiff) / 2;
                        } else {
                            mAvgNow1Ms = nowDiff;
                        }

                        // Display timing stats
                        String d = String.format("G:%3d [%d L:%3d ms F:%3d +E:%3d ms] %s",
                                mGlobalAvgNowMs,    // average millis between global sensor updates
                                mSensorCount,        // update count for this sensor
                                mAvgNowMs,            // average milis between updates for this sensor
                                mAvgNow1Ms,            // average millis for string.format + emu send
                                mSensor.getName());
                        Log.d(TAG, d);

                    } else {
                        mLastNowTS = now;
                    }
                } // VERBOSE_TIMING
            }

            /**
             * Handles "sensor accuracy changed" event. This is an implementation of
             * the SensorEventListener interface.
             */
            @Override
            public void onAccuracyChanged(Sensor sensor, int accuracy) {
            }

            // Debug: Per-sensor variables used for verbose timing. see VERBOSE_TIMING
            private int mSensorCount;
            private long mLastNowTS;
            private long mAvgNowMs;
            private long mAvgNow1Ms;

        }
    } // MonitoredSensor

    // Debug: Global variables used for verbose timing. see VERBOSE_TIMING
    private long mGlobalLastNowTS;
    private long mGlobalAvgNowMs;

}
