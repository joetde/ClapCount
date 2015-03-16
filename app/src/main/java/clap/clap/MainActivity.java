
package clap.clap;

import android.app.Activity;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.TextView;


public class MainActivity extends Activity {

    private static final int WORKER_PERIOD = 5; // in ms, approx value, does not count processing time
    private static final int COOLDOWN = 200; // in ms, cooldown time (precision: WORKER_PERIOD)
    private static final float INCREASE_RATIO_THRESHOLD = 2f; // ratio threshold to consider sound start
    private static final float DECREASE_RATIO_THRESHOLD = -.4f; // ratio threshold to consider sound end
    private static final int SAMPLING_RATE = 44100; // in hertz, recorder frequency
    private static final int SAMPLING_BUFFER = 512; // how many sample to take each cycle (should not exceed BUFFER_SIZE)
    private static final int CLAP_MAX_DURATION = 100; // in ms, max time to consider a clap (precision: WORKER_PERIOD)
    private static final int RECORDER_CHANNELS = AudioFormat.CHANNEL_IN_MONO;
    private static final int RECORDER_AUDIO_ENCODING = AudioFormat.ENCODING_PCM_16BIT;
    private static final int BUFFER_SIZE = AudioRecord.getMinBufferSize(SAMPLING_RATE,RECORDER_CHANNELS, RECORDER_AUDIO_ENCODING);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        /*Log.i("ClapActivity.Init",
                "Clap Counter started with configurations:"+
                "\n\tWorker period:   "+WORKER_PERIOD+
                "\n\tCooldown cycles: "+COOLDOWN+
                "\n\tThreshold ratio: "+INCREASE_RATIO_THRESHOLD+
                "\n\tSampling rate:   "+SAMPLING_RATE+
                "\n\tBuffer size:     "+BUFFER_SIZE);*/
        init();
    }

    @Override
    protected void onResume() {
        super.onResume();
        start();
    }

    @Override
    protected  void onPause() {
        super.onPause();
        stop();
    }

    /**
     * Activity initialization
     */
    private void init() {
        _lastTime = System.currentTimeMillis();
        initRecorder();
        resetCount();
    }

    /**
     * Start
     */
    private void start() {
        startRecorder();
        startWorker();
    }

    /**
     * Stop
     */
    private void stop() {
        stopWorker();
        _recorder.stop();
    }

    /**
     * Recorder initialization (no kidding!)
     */
    private void initRecorder() {
        _recorder = new AudioRecord(MediaRecorder.AudioSource.MIC,
                SAMPLING_RATE,
                RECORDER_CHANNELS,
                RECORDER_AUDIO_ENCODING,
                BUFFER_SIZE);
    }

    /**
     * Start recorder
     */
    private void startRecorder() {
        _recorder.startRecording();
    }

    /**
     * Start clap thread
     */
    private void startWorker() {
        _worker = new Thread(new Runnable() {
            public void run() { clapWorker(); }
        });
        _workerRunning = true;
        _worker.start();
    }

    /**
     * Stop clap thread
     */
    private void stopWorker() {
        _workerRunning = false;
        try {
            _worker.join();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        _worker = null;
    }

    /**
     * Reset count and refresh screen, deactivate clap count for COOLDOWN cycles
     * @see MainActivity#resetCount(View)
     */
    public void resetCount() {
        resetCount(null);
    }

    /**
     * Reset count and refresh screen, deactivate clap count for COOLDOWN cycles
     * @param v unused
     */
    public void resetCount(View v) {
        _count = 0;
        refreshCount();
        _lastReset = System.currentTimeMillis();
    }

    /**
     * Main method to treat incoming signal and update clap count
     * Worker routine
     */
    private void clapWorker() {
        while (_workerRunning) {
            //time();
            try {
                Thread.sleep(WORKER_PERIOD);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            int energy = getEnergy();
            if ((System.currentTimeMillis() - _lastReset) < COOLDOWN) {_lastEnergy = energy; continue; }
            float relativeIncrease = (float)energy / (float)_lastEnergy - 1f;
            //Log.d("ClapWorker.Input.Energy", "" + energy);
            //Log.d("ClapWorker.Input.Energy", "" + relativeIncrease);
            if (_peakOriginEnergy == 0 && relativeIncrease > INCREASE_RATIO_THRESHOLD) {
                // strong increase
                _peakStart = System.currentTimeMillis();
                _peakOriginEnergy = _lastEnergy;
                //Log.d("ClapWorker.Input", "Increase detected " + relativeIncrease + ", starts from energy " + _peakOriginEnergy);
            } else if (_peakOriginEnergy != 0 && relativeIncrease < DECREASE_RATIO_THRESHOLD) {
                // strong decrease after strong increase... (sounds very boring)
                long peakDuration = System.currentTimeMillis() - _peakStart;
                if (peakDuration < CLAP_MAX_DURATION) {
                    // short sound, count
                    //Log.i("ClapWorker.Output", "Clap counted");
                    incrementClap();
                } else if (peakDuration > 3 * CLAP_MAX_DURATION) {
                    // long sound, reset
                    //Log.i("ClapWorker.Output", "Long sound, reset");
                    resetCount();
                }
                //Log.d("ClapWorker.Input", "End of peak reached energy " + energy + " duration " + peakDuration);
                _peakOriginEnergy = 0;
            } else if (_peakOriginEnergy != 0 && energy < _peakOriginEnergy * 1.1f) {
                // make sure to reset if we reach normal energy level
                _peakOriginEnergy = 0;
            }
            _lastEnergy = energy;
        }
    }

    /**
     * Display time information for the cycle
     */
    private void time() {
        long newTime = System.currentTimeMillis();
        Log.d("ClapWorker.Timing.Cycle", "Last cycle ran in "+(newTime-_lastTime)+"ms");
        _lastTime = newTime;
    }

    /**
     * Get the current sound energy (empty the recorder buffer)
     * @return energy
     */
    private int getEnergy() {
        short soundSamples[] = new short[BUFFER_SIZE];
        int dataRead = _recorder.read(soundSamples, 0, SAMPLING_BUFFER);
        int energy = 0;
        for (int i=0; i<dataRead; ++i) {
            energy += Math.abs(soundSamples[i]);
        }
        return energy;
   }

    /**
     * Refresh count on screen
     */
    private void refreshCount() {
        TextView clapsCount =  (TextView) findViewById(R.id.claps);
        TextView clapsText  =  (TextView) findViewById(R.id.textClaps);
        postNewText(clapsCount, Integer.toString(_count));
        if (_count <= 1) { postNewText(clapsText, "clap"); }
        else             { postNewText(clapsText, "claps"); }
    }

    /**
     * Async UI refresh for TextView
     * @param tv TextView to update
     * @param newText new text to set in the TextView
     */
    private void postNewText(final TextView tv, final String newText) {
        tv.post(new Runnable() {
            public void run() {
                tv.setText(newText);
            }
        });
    }

    /**
     * Well... it does what it says... And refresh the UI.
     */
    private void incrementClap() {
        ++_count;
        refreshCount();
    }

    private AudioRecord _recorder; // recording object
    private Thread _worker; // working thread
    private boolean _workerRunning = false; // is the worker running?
    private int _count = 0; // number of clap counted so far

    private int _lastEnergy = 0; // energy computed last cycle

    private long _lastReset = 0; // time (ms) of the last reset
    private long _lastTime;
    private long _peakStart;
    private int _peakOriginEnergy = 0;
}
