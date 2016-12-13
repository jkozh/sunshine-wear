/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example.sunshinewatchface;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.annotation.Nullable;
import android.support.v4.content.ContextCompat;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.WindowInsets;

import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.wearable.DataApi;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataItem;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.Wearable;

import java.lang.ref.WeakReference;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

/**
 * Digital watch face with seconds. In ambient mode, the seconds aren't displayed. On devices with
 * low-bit ambient mode, the text is drawn without anti-aliasing in ambient mode.
 */
public class DigitalWatchFace extends CanvasWatchFaceService {

    private static String LOG_TAG = DigitalWatchFace.class.getSimpleName();

    private static final Typeface NORMAL_TYPEFACE =
            Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL);

    /**
     * Update rate in milliseconds for interactive mode. We update once a second since seconds are
     * displayed in interactive mode.
     */
    private static final long INTERACTIVE_UPDATE_RATE_MS = TimeUnit.SECONDS.toMillis(1);

    /**
     * Handler message id for updating the time periodically in interactive mode.
     */
    private static final int MSG_UPDATE_TIME = 0;

    @Override
    public Engine onCreateEngine() {
        return new Engine();
    }

    private static class EngineHandler extends Handler {
        private final WeakReference<DigitalWatchFace.Engine> mWeakReference;

        EngineHandler(DigitalWatchFace.Engine reference) {
            mWeakReference = new WeakReference<>(reference);
        }

        @Override
        public void handleMessage(Message msg) {
            DigitalWatchFace.Engine engine = mWeakReference.get();
            if (engine != null) {
                switch (msg.what) {
                    case MSG_UPDATE_TIME:
                        engine.handleUpdateTimeMessage();
                        break;
                }
            }
        }
    }

    private class Engine extends CanvasWatchFaceService.Engine implements DataApi.DataListener,
            GoogleApiClient.ConnectionCallbacks {

        private static final String WEATHER_PATH = "/WEATHER_PATH";
        private static final String HIGH_TEMPERATURE = "HIGH_TEMPERATURE";
        private static final String LOW_TEMPERATURE = "LOW_TEMPERATURE";

        final Handler mUpdateTimeHandler = new EngineHandler(this);
        boolean mRegisteredTimeZoneReceiver = false;

        // graphic objects
        Paint mBackgroundPaint;
        Paint mTimePaint;
        Paint mDatePaint;
        Paint mIconPaint;
        Paint mHighTemperaturePaint;
        Paint mLowTemperaturePaint;


        Calendar mCalendar;
        // receiver to update the time zone
        final BroadcastReceiver mTimeZoneReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mCalendar.setTimeZone(TimeZone.getDefault());
                invalidate();
            }
        };

        // for positioning elements
        float mTimeYOffset;
        float mDateYOffset;
        float mHighTemperatureYOffset;
        float mLowTemperatureYOffset;
        float mIconXOffset;
        float mIconYOffset;

        /**
         * Whether the display supports fewer bits for each color in ambient mode. When true, we
         * disable anti-aliasing in ambient mode.
         */
        boolean mAmbient;
        boolean mLowBitAmbient;

        String mHighTemperature;
        String mLowTemperature;
        String mDate;
        Bitmap mIcon;

        GoogleApiClient mGoogleApiClient;

        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);

            mGoogleApiClient = new GoogleApiClient.Builder(DigitalWatchFace.this)
                    .addApi(Wearable.API)
                    .addConnectionCallbacks(this)
                    .build();

            setWatchFaceStyle(new WatchFaceStyle.Builder(DigitalWatchFace.this)
                    .setCardPeekMode(WatchFaceStyle.PEEK_MODE_VARIABLE)
                    .setBackgroundVisibility(WatchFaceStyle.BACKGROUND_VISIBILITY_INTERRUPTIVE)
                    .setShowSystemUiTime(false)
                    .build());

            Resources resources = DigitalWatchFace.this.getResources();

            mBackgroundPaint = new Paint();
            // set the background color
            mBackgroundPaint.setColor(ContextCompat.getColor(
                    getApplicationContext(), R.color.background));

            mTimeYOffset = resources.getDimension(R.dimen.digital_time_y_offset);
            mTimePaint = new Paint();
            // set color of time
            mTimePaint = createTextPaint(ContextCompat.getColor(
                    getApplicationContext(), R.color.digital_text));

            mHighTemperatureYOffset = resources.getDimension(R.dimen.temperature_high_y_offset);
            mHighTemperaturePaint = new Paint();
            mHighTemperaturePaint = createTextPaint(ContextCompat.getColor(
                    getApplicationContext(), R.color.digital_text));

            mLowTemperatureYOffset = resources.getDimension(R.dimen.temperature_low_y_offset);
            mLowTemperaturePaint = new Paint();
            mLowTemperaturePaint = createTextPaint(ContextCompat.getColor(
                    getApplicationContext(), R.color.date_text));

            mDateYOffset = resources.getDimension(R.dimen.date_y_offset);
            mDatePaint = new Paint();
            mDatePaint = createTextPaint(ContextCompat.getColor(
                    getApplicationContext(), R.color.date_text));

            mIconPaint = new Paint();
            mIconYOffset = resources.getDimension(R.dimen.icon_y_offset);
            float scaleToUse = 0.4f;
            mIcon = BitmapFactory.decodeResource(resources, R.drawable.art_clear);
            float sizeY = (float) mIcon.getHeight() * scaleToUse;
            float sizeX = (float) mIcon.getWidth() * scaleToUse;
            mIcon = Bitmap.createScaledBitmap(mIcon, (int) sizeX, (int) sizeY, false);

            // allocate a Calendar to calculate local time using the UTC time and time zone
            mCalendar = Calendar.getInstance();


        }

        @Override
        public void onDestroy() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            super.onDestroy();
        }

        private Paint createTextPaint(int textColor) {
            Paint paint = new Paint();
            paint.setColor(textColor);
            paint.setTypeface(NORMAL_TYPEFACE);
            paint.setAntiAlias(true);
            return paint;
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);

            if (visible) {

                mGoogleApiClient.connect();

                registerReceiver();

                // Update time zone in case it changed while we weren't visible.
                mCalendar.setTimeZone(TimeZone.getDefault());
                invalidate();
            } else {
                if (mGoogleApiClient != null && mGoogleApiClient.isConnected()) {
                    Wearable.DataApi.removeListener(mGoogleApiClient, this);
                    mGoogleApiClient.disconnect();
                }
                unregisterReceiver();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        private void registerReceiver() {
            if (mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = true;
            IntentFilter filter = new IntentFilter(Intent.ACTION_TIMEZONE_CHANGED);
            DigitalWatchFace.this.registerReceiver(mTimeZoneReceiver, filter);
        }

        private void unregisterReceiver() {
            if (!mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = false;
            DigitalWatchFace.this.unregisterReceiver(mTimeZoneReceiver);
        }

        @Override
        public void onApplyWindowInsets(WindowInsets insets) {
            super.onApplyWindowInsets(insets);

            // Load resources that have alternate values for round watches.
            Resources resources = DigitalWatchFace.this.getResources();
            boolean isRound = insets.isRound();

            float timeTextSize = resources.getDimension(isRound
                    ? R.dimen.digital_time_text_size_round : R.dimen.digital_time_text_size);

            mTimePaint.setTextSize(timeTextSize);

            float dateTextSize = resources.getDimension(isRound
                    ? R.dimen.date_text_size_round : R.dimen.date_text_size);

            mDatePaint.setTextSize(dateTextSize);

            float temperatureTextSize = resources.getDimension(isRound
                    ? R.dimen.temperature_text_size_round : R.dimen.temperature_text_size);

            mHighTemperaturePaint.setTextSize(temperatureTextSize);
            mLowTemperaturePaint.setTextSize(temperatureTextSize);

            mIconXOffset = resources.getDimension(isRound
            ? R.dimen.icon_x_offset_round : R.dimen.icon_x_offset);

        }

        @Override
        public void onPropertiesChanged(Bundle properties) {
            super.onPropertiesChanged(properties);
            mLowBitAmbient = properties.getBoolean(PROPERTY_LOW_BIT_AMBIENT, false);
        }

        @Override
        public void onTimeTick() {
            super.onTimeTick();
            invalidate();
        }

        @Override
        public void onAmbientModeChanged(boolean inAmbientMode) {
            super.onAmbientModeChanged(inAmbientMode);
            if (mAmbient != inAmbientMode) {
                mAmbient = inAmbientMode;
                if (mLowBitAmbient) {
                    mTimePaint.setAntiAlias(!inAmbientMode);
                }
                invalidate();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        @Override
        public void onDraw(Canvas canvas, Rect bounds) {
            // Draw the background.
            if (isInAmbientMode()) {
                canvas.drawColor(Color.BLACK);
            } else {
                canvas.drawRect(0, 0, bounds.width(), bounds.height(), mBackgroundPaint);
            }

            // Draw H:MM in ambient mode or H:MM:SS in interactive mode.
            long now = System.currentTimeMillis();
            mCalendar.setTimeInMillis(now);

            // current time
            String time = String.format(Locale.getDefault(), "%d:%02d",
                    mCalendar.get(Calendar.HOUR), mCalendar.get(Calendar.MINUTE));
            canvas.drawText(
                    time,
                    bounds.centerX() - mTimePaint.measureText(time)/2,
                    mTimeYOffset,
                    mTimePaint);

            // high temperature
            mHighTemperature = "25";
            canvas.drawText(
                    mHighTemperature,
                    bounds.centerX() - mHighTemperaturePaint.measureText(mHighTemperature)/2,
                    mHighTemperatureYOffset,
                    mHighTemperaturePaint);

            // low temperature
            mLowTemperature = "16";
            canvas.drawText(
                    mLowTemperature,
                    bounds.centerX() - mLowTemperaturePaint.measureText(mLowTemperature)/2
                            + mHighTemperaturePaint.measureText(mHighTemperature),
                    mLowTemperatureYOffset,
                    mLowTemperaturePaint);

            // current date
            DateFormat df = new SimpleDateFormat("EEE, MMM d yyyy", Locale.getDefault());
            mDate = df.format(mCalendar.getTime()).toUpperCase();
            canvas.drawText(
                    mDate,
                    bounds.centerX() - mDatePaint.measureText(mDate)/2,
                    mDateYOffset,
                    mDatePaint);

            // weather icon
            canvas.drawBitmap(
                    mIcon,
                    mIconXOffset,
                    mIconYOffset,
                    mIconPaint);

        }

        /**
         * Starts the {@link #mUpdateTimeHandler} timer if it should be running and isn't currently
         * or stops it if it shouldn't be running but currently is.
         */
        private void updateTimer() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            if (shouldTimerBeRunning()) {
                mUpdateTimeHandler.sendEmptyMessage(MSG_UPDATE_TIME);
            }
        }

        /**
         * Returns whether the {@link #mUpdateTimeHandler} timer should be running. The timer should
         * only run when we're visible and in interactive mode.
         */
        private boolean shouldTimerBeRunning() {
            return isVisible() && !isInAmbientMode();
        }

        /**
         * Handle updating the time periodically in interactive mode.
         */
        private void handleUpdateTimeMessage() {
            invalidate();
            if (shouldTimerBeRunning()) {
                long timeMs = System.currentTimeMillis();
                long delayMs = INTERACTIVE_UPDATE_RATE_MS
                        - (timeMs % INTERACTIVE_UPDATE_RATE_MS);
                mUpdateTimeHandler.sendEmptyMessageDelayed(MSG_UPDATE_TIME, delayMs);
            }
        }

        void fetchWeatherData() {

        }

        @Override
        public void onConnected(@Nullable Bundle bundle) {
            Log.d(LOG_TAG, "onConnected");
            Wearable.DataApi.addListener(mGoogleApiClient, Engine.this);

            fetchWeatherData();
        }

        @Override
        public void onConnectionSuspended(int i) {
            Log.d(LOG_TAG, "onConnectionSuspended");
        }

        @Override
        public void onDataChanged(DataEventBuffer dataEventBuffer) {
            for (DataEvent event : dataEventBuffer) {
                if (event.getType() == DataEvent.TYPE_CHANGED) {
                    DataItem dataItem = event.getDataItem();
                    if (dataItem.getUri().getPath().compareTo(WEATHER_PATH) == 0) {

                        DataMap dataMap = DataMapItem.fromDataItem(dataItem).getDataMap();

                        if (dataMap.containsKey(HIGH_TEMPERATURE)) {
                            mHighTemperature = dataMap.getString(HIGH_TEMPERATURE);
                            Log.d(LOG_TAG, "High temperature: " + mHighTemperature);
                        } else {
                            Log.d(LOG_TAG, "No high temperature!");
                        }

                        if (dataMap.containsKey(LOW_TEMPERATURE)) {
                            mLowTemperature = dataMap.getString(LOW_TEMPERATURE);
                            Log.d(LOG_TAG, "Low temperature: " + mLowTemperature);
                        } else {
                            Log.d(LOG_TAG, "No low temperature!");
                        }

                        invalidate();
                    }
                }
            }
        }
    }
}
