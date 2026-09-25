package com.appgarage.dash;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.MotionEvent;
import android.view.View;

/**
 * Vehicle gauge dashboard for the 800x480 InTouch head-unit screen. Reads RAW
 * VS_ID_* sensor values (set via setValue(type, raw)) and renders calibrated gauges.
 *
 * Calibration (locked from on-car data + the DrivingPerformance reference app):
 *   RPM(13) = rpm, direct.        COOLANT(14) / OILTEMP(15) = degC, direct.
 *   TPMS(36-39) = psi, direct.    TORQUE(12) ~ Nm.
 *   POWER(32) = rpm*Nm  -> kW  = raw * 1.047e-4.
 *   OILPRESS(16) = MPa  -> psi = raw * 145  (confirmed ~22 psi warm idle).
 *   SPEED(17) = km/h    -> mph = raw * 0.621.
 *   GEAR(22) enum: P/R/N/D = 1/2/3/4, manual M1..M7 = 16..22 (confirmed on-car).
 *   G lat(20)/long(21): raw, ~1g full-scale assumed (not yet calibrated).
 *
 * v1.1: tap anywhere to flip the whole dash between imperial and metric (persisted);
 * oil pressure hides itself when it reads negative (a wrong-engine signal, e.g. VQ);
 * a loud banner is drawn when the values are the built-in demo, not live CAN.
 */
public class GaugeView extends View {

    // sensor types
    private static final int TORQUE=12, RPM=13, COOLANT=14, OILT=15, OILP=16, SPEED=17,
            GLAT=20, GLONG=21, GEAR=22, THROTTLE=23, POWER=32,
            TP_FR=36, TP_FL=37, TP_RR=38, TP_RL=39;

    // scaling constants (calibrated on-car). Oil-pressure raw is MPa: x145 -> psi, or x10 -> bar.
    public static float OILP_RAW_TO_PSI = 145.0377f;   // MPa -> psi (~22 psi warm idle)
    public static float POWER_RAW_TO_KW = 0.0001047f;  // raw = rpm*Nm -> kW
    public static float SPEED_RAW_TO_MPH = 0.621371f;  // km/h -> mph
    // unit conversions for the imperial/metric toggle
    static final float PSI_TO_BAR  = 0.0689476f;
    static final float KW_TO_HP    = 1.34102f;
    static final float NM_TO_LBFT  = 0.737562f;

    private static final int N = 64;
    private final float[] v = new float[N];
    private final boolean[] have = new boolean[N];
    private String status = "";
    private boolean demo = false;                 // true => values are seedDemo(), not live CAN
    private boolean imperial = true;              // tap to flip; default imperial (persisted)
    private static final String PREFS = "agdash", KEY_IMP = "imperial";
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF oval = new RectF();

    // colors
    private static final int BG=0xFF0B0E12, PANEL=0xFF151B22, LABEL=0xFF7FB0FF,
            VAL=0xFFFFFFFF, DIM=0xFF6A7684, OK=0xFF37E07A, WARN=0xFFFFB020, DANGER=0xFFFF4040,
            ARC_BG=0xFF243040, ARC_FG=0xFF39C0FF;

    public GaugeView(Context c) {
        super(c);
        setBackgroundColor(BG);
        p.setTypeface(Typeface.MONOSPACE);
        setClickable(true);
        try { imperial = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_IMP, true); }
        catch (Throwable ignored) {}
    }

    /** tap anywhere -> flip units, remember the choice. */
    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (e.getAction() == MotionEvent.ACTION_UP) {
            imperial = !imperial;
            try { getContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putBoolean(KEY_IMP, imperial).commit(); } catch (Throwable ignored) {}
            invalidate();
        }
        return true;
    }

    public void setName(int t, String n) {}                // labels are hardcoded
    public void setValue(int t, float val) { if (t>=0 && t<N) { v[t]=val; have[t]=true; } }
    public void setStatus(String s) { status = s; }
    public void setDemo(boolean d) { demo = d; }
    private float g(int t) { return have[t] ? v[t] : 0f; }
    private boolean h(int t) { return have[t]; }
    private static float cToF(float c) { return c*9f/5f + 32f; }

    /** seed representative values so the layout is visible on an emulator (no vehicle bus). */
    public void seedDemo() {
        int[] t = {TORQUE,RPM,COOLANT,OILT,OILP,SPEED,GLAT,GLONG,GEAR,THROTTLE,POWER,TP_FR,TP_FL,TP_RR,TP_RL};
        float[] val = {180f,3120f,92f,105f,0.42f,68f,0.35f,-0.20f,3f,42f,3120f*180f,38.5f,38.5f,37f,36.8f};
        for (int i=0;i<t.length;i++) setValue(t[i], val[i]);
        demo = true;
        status = "DEMO (no vehicle bus) — sample values, not live";
    }

    @Override
    protected void onDraw(Canvas cv) {
        int W=getWidth(), H=getHeight();
        p.setColor(BG); cv.drawRect(0,0,W,H,p);

        drawRpm(cv, 150, 170, 132);                          // hero arc, left

        // right column: temp/pressure bars
        int bx=330, bw=W-bx-16;
        if (imperial) {
            drawBar(cv, bx, 24,  bw, "OIL TEMP", cToF(g(OILT)),   cToF(40),cToF(150), cToF(120),cToF(140), "°F", h(OILT));
            drawBar(cv, bx, 78,  bw, "COOLANT",  cToF(g(COOLANT)),cToF(40),cToF(130), cToF(110),cToF(120), "°F", h(COOLANT));
        } else {
            drawBar(cv, bx, 24,  bw, "OIL TEMP", g(OILT),   40,150, 120,140, "°C", h(OILT));
            drawBar(cv, bx, 78,  bw, "COOLANT",  g(COOLANT),40,130, 110,120, "°C", h(COOLANT));
        }

        // oil pressure: hide when negative (a wrong-engine signal, e.g. VQ reported -14 psi)
        float oilPsi = g(OILP)*OILP_RAW_TO_PSI;
        boolean oilHas = h(OILP) && oilPsi >= 0f;
        if (imperial)
            drawBar(cv, bx, 132, bw, "OIL PRESS", oilPsi,            0,100,               90,100,               "psi", oilHas);
        else
            drawBar(cv, bx, 132, bw, "OIL PRESS", oilPsi*PSI_TO_BAR, 0,100*PSI_TO_BAR, 90*PSI_TO_BAR,100*PSI_TO_BAR, "bar", oilHas);

        // speed + gear + throttle row
        if (imperial) drawBigNum(cv, bx, 196, "SPEED", fmt0(g(SPEED)*SPEED_RAW_TO_MPH), "mph",  h(SPEED));
        else          drawBigNum(cv, bx, 196, "SPEED", fmt0(g(SPEED)),                  "km/h", h(SPEED));
        drawBigNum(cv, bx+180,  196, "GEAR",  gearStr(),          "",     h(GEAR));
        drawBigNum(cv, bx+300,  196, "THR",   fmt0(g(THROTTLE)),  "%",    h(THROTTLE));

        // power + torque
        p.setColor(LABEL); p.setTextSize(14f); cv.drawText("POWER", bx, 300, p);
        p.setColor(VAL);   p.setTextSize(26f);
        String pw = imperial ? fmt0(g(POWER)*POWER_RAW_TO_KW*KW_TO_HP)+" hp" : fmt0(g(POWER)*POWER_RAW_TO_KW)+" kW";
        cv.drawText(pw, bx+70, 302, p);
        p.setColor(LABEL); p.setTextSize(14f); cv.drawText("TORQUE", bx, 328, p);
        p.setColor(VAL);   p.setTextSize(26f);
        String tq = imperial ? fmt0(g(TORQUE)*NM_TO_LBFT)+" lb-ft" : fmt0(g(TORQUE))+" Nm";
        cv.drawText(tq, bx+70, 330, p);

        drawTpms(cv, 16, 350, 300);                          // bottom-left TPMS corners
        drawGball(cv, W-120, 360, 90);                       // bottom-right G-ball

        // status footer + units hint
        p.setColor(DIM); p.setTextSize(11f);
        cv.drawText(status, 16, H-8, p);
        String u = (imperial?"IMPERIAL":"METRIC") + " — tap to switch";
        cv.drawText(u, W - p.measureText(u) - 8, H-8, p);

        // loud demo banner drawn last so it can't be missed
        if (demo) {
            p.setColor(WARN); cv.drawRect(0, 0, W, 22, p);
            p.setColor(0xFF101010); p.setTextSize(14f);
            center(cv, "DEMO — NO LIVE CAN DETECTED (sample values, not your car)", W/2, 16);
        }
    }

    private void drawRpm(Canvas cv, int cx, int cy, int r) {
        float val=g(RPM), max=7500, red=6800, start=135, sweep=270;   // VR30: peak power @6400, rev limit ~6800
        oval.set(cx-r, cy-r, cx+r, cy+r);
        p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(18f);
        p.setColor(ARC_BG); cv.drawArc(oval, start, sweep, false, p);
        // redline zone
        p.setColor(0x66FF4040); cv.drawArc(oval, start+sweep*(red/max), sweep*(1-red/max), false, p);
        // value arc
        float frac=Math.max(0,Math.min(1,val/max));
        p.setColor(val>=red?DANGER:ARC_FG); cv.drawArc(oval, start, sweep*frac, false, p);
        p.setStyle(Paint.Style.FILL);
        // digital center
        p.setColor(LABEL); p.setTextSize(16f); center(cv,"RPM",cx,cy-42);
        p.setColor(val>=red?DANGER:VAL); p.setTextSize(64f); center(cv, h(RPM)?fmt0(val):"--", cx, cy+18);
        p.setColor(DIM); p.setTextSize(14f); center(cv,"redline 6800",cx,cy+r-6);
    }

    private void drawBar(Canvas cv,int x,int y,int w,String lab,float val,float min,float max,
                         float warn,float red,String unit,boolean has){
        int h=26;
        p.setColor(LABEL); p.setTextSize(15f); cv.drawText(lab, x, y-6, p);
        p.setColor(PANEL); cv.drawRect(x, y, x+w, y+h, p);
        float frac=Math.max(0,Math.min(1,(val-min)/(max-min)));
        int c = val>=red?DANGER : val>=warn?WARN : OK;
        if(has){ p.setColor(c); cv.drawRect(x, y, x+w*frac, y+h, p); }
        p.setColor(VAL); p.setTextSize(20f);
        String s = has ? (fmt1(val)+" "+unit) : "--";
        cv.drawText(s, x+w-p.measureText(s)-8, y+20, p);
    }

    private void drawBigNum(Canvas cv,int x,int y,String lab,String val,String unit,boolean has){
        p.setColor(LABEL); p.setTextSize(14f); cv.drawText(lab, x, y-4, p);
        p.setColor(has?VAL:DIM); p.setTextSize(40f); cv.drawText(has?val:"--", x, y+34, p);
        if(has){ p.setColor(DIM); p.setTextSize(14f); cv.drawText(unit, x+2, y+50, p); }
    }

    private void drawTpms(Canvas cv,int x,int y,int w){
        p.setColor(LABEL); p.setTextSize(14f); cv.drawText(imperial?"TPMS (psi)":"TPMS (bar)", x, y-4, p);
        String[] lab={"FL","FR","RL","RR"}; int[] typ={TP_FL,TP_FR,TP_RL,TP_RR};
        int cw=w/2, ch=44;
        for(int i=0;i<4;i++){
            int cx=x+(i%2)*cw, cy=y+(i/2)*ch;
            p.setColor(DIM); p.setTextSize(13f); cv.drawText(lab[i], cx, cy+18, p);
            float raw=g(typ[i]);                                  // raw is psi
            boolean has=have[typ[i]];
            int c = (has && (raw<30||raw>42))?WARN:VAL;           // warn thresholds stay in psi
            float disp = imperial ? raw : raw*PSI_TO_BAR;
            p.setColor(has?c:DIM); p.setTextSize(24f);
            cv.drawText(has?fmt1(disp):"--", cx+34, cy+20, p);
        }
    }

    private void drawGball(Canvas cv,int cx,int cy,int r){
        p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(2f); p.setColor(ARC_BG);
        cv.drawCircle(cx,cy,r,p); cv.drawCircle(cx,cy,r/2,p);
        cv.drawLine(cx-r,cy,cx+r,cy,p); cv.drawLine(cx,cy-r,cx,cy+r,p);
        p.setStyle(Paint.Style.FILL); p.setColor(LABEL); p.setTextSize(12f); center(cv,"G",cx,cy-r-4);
        // long accel = vertical (fwd up), lat = horizontal; assume ~1g full-scale
        float gx=Math.max(-1,Math.min(1,g(GLAT))), gy=Math.max(-1,Math.min(1,g(GLONG)));
        p.setColor(OK); cv.drawCircle(cx+gx*r, cy-gy*r, 6f, p);
    }

    private void center(Canvas cv,String s,int cx,int y){ cv.drawText(s, cx-p.measureText(s)/2, y, p); }
    private String fmt0(float f){ return String.valueOf(Math.round(f)); }
    private String fmt1(float f){ return String.valueOf(Math.round(f*10f)/10f); }
    // GEAR_POSITION enum (confirmed on-car): P/R/N/D = 1/2/3/4; manual mode M1..M7 = 16..22.
    private String gearStr(){
        if(!h(GEAR)) return "--";
        int gg=Math.round(g(GEAR));
        switch(gg){
            case 1: return "P";
            case 2: return "R";
            case 3: return "N";
            case 4: return "D";
            default: return (gg>=16 && gg<=22) ? "M"+(gg-15) : String.valueOf(gg);
        }
    }
}
