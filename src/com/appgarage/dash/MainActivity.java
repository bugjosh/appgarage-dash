package com.appgarage.dash;

import android.app.Activity;
import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.view.WindowManager;

import java.util.List;

/**
 * AppGarage Dash — a dongle-free vehicle dashboard for the Infiniti InTouch head unit
 * (V37 Q50 / Q60 running the Android 2.3.x InTouch revision). The head unit exposes the
 * car's CAN signals (RPM, oil temp/pressure, coolant, speed, G, gear, throttle, power,
 * TPMS ...) as standard Android Sensors (VS_ID_*, vendor "Ygomi"), types 12-53. We
 * register a listener on every one and hand live values to GaugeView, which renders the
 * calibrated gauges. Pure Java, no native libs, minSdk 10 -> runs on the x86 API-10 unit.
 *
 * v1.1: fast-changing signals get a game-rate subscription (snappier RPM); we unregister
 * before re-subscribing and register only from onResume (fixes a signal dropping on the
 * second launch); GaugeView paints an obvious banner when no live CAN is present.
 */
public class MainActivity extends Activity implements SensorEventListener {

    // Fast-changing signals -> game-rate; everything else stays lazy (200 ms). The bus's own
    // broadcast rate is the real ceiling, but the flat 5 Hz in v1.0 was an artificial cap.
    // 12 torque, 13 rpm, 17 speed, 20/21 G lat/long, 22 gear, 23 throttle, 24 brake, 25 steering, 32 power.
    private static final int[] FAST = {12, 13, 17, 20, 21, 22, 23, 24, 25, 32};
    private static final int SLOW_US = 200000;   // 200 ms for temps / TPMS / flags / settings

    private SensorManager sm;
    private GaugeView view;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        view = new GaugeView(this);
        setContentView(view);
        sm = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        // NB: registration happens in onResume (always runs right after onCreate). Registering
        // here too would double-subscribe every sensor on first launch.
    }

    private static boolean isFast(int t) { for (int f : FAST) if (f == t) return true; return false; }

    private void registerAll() {
        if (sm == null) { view.setStatus("SENSOR_SERVICE = null"); return; }
        try { sm.unregisterListener(this); } catch (Throwable ignored) {}   // drop stale subs before re-adding
        int n = 0; boolean hasVehicleBus = false;
        try {
            List<Sensor> all = sm.getSensorList(Sensor.TYPE_ALL);
            if (all != null) for (Sensor s : all) {
                int type = s.getType();
                view.setName(type, s.getName());
                if (type == 13) hasVehicleBus = true;                       // 13 = ENGINE_RPM
                int rate = isFast(type) ? SensorManager.SENSOR_DELAY_GAME : SLOW_US;
                try { sm.registerListener(this, s, rate); n++; } catch (Throwable ignored) {}
            }
        } catch (Throwable t) { view.setStatus("getSensorList error: " + t); }
        if (!hasVehicleBus) view.seedDemo();                               // emulator / no CAN -> obvious demo banner
        else { view.setDemo(false); view.setStatus(n + " CAN signals live"); }
        view.invalidate();
    }

    @Override protected void onResume() { super.onResume(); registerAll(); }
    @Override protected void onPause()  { super.onPause(); try { sm.unregisterListener(this); } catch (Throwable ignored) {} }

    @Override
    public void onSensorChanged(SensorEvent e) {
        try {
            float v = (e.values != null && e.values.length > 0) ? e.values[0] : 0f;
            view.setValue(e.sensor.getType(), v);
            view.invalidate();
        } catch (Throwable ignored) {}
    }

    @Override public void onAccuracyChanged(Sensor s, int a) {}
}
