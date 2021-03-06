package com.example.taichiabe.receiver_superpos;
/*=========================================================*
 * システム：受信・リアルタイムFFT
 * Receiver Superposition
 *==========================================================*/
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import android.app.Activity;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.os.Vibrator;
import android.widget.CompoundButton;
import android.widget.CompoundButton.OnCheckedChangeListener;
import android.widget.Switch;
import android.widget.EditText;
import android.widget.TextView;

public class MainActivity extends Activity implements OnCheckedChangeListener {

    //サンプリングレート
    public static final int SAMPLING_RATE = 44100;
    //FFTのポイント数（2の累乗）
    public static final int FFT_SIZE = 4096;
    //デシベルベースラインの設定
    public static final double DB_BASELINE = Math.pow(2, 15) * FFT_SIZE * Math.sqrt(2);
    //分解能の計算
    public static final double RESOLUTION = SAMPLING_RATE / (double) FFT_SIZE;
    private Vibrator vib;
    private AudioRecord audioRec = null;
    boolean isRecording = false;
    Thread fft;
    private TimeMeasure tm;
    //アベレージング回数
    int spCounter;
    double[][] spSpectrum;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        TextView receivingFreqText = findViewById(R.id.receivingFreqText);
        receivingFreqText.setText(R.string.receivingFreqText);
        TextView decibelDiffText = findViewById(R.id.decibelDiffText);
        decibelDiffText.setText(R.string.decibelDiffText);
        TextView superpositionText = findViewById(R.id.superpositionText);
        superpositionText.setText(R.string.superpositionText);
        Switch receivingSwitch = findViewById(R.id.receivingSwitch);
        receivingSwitch.setOnCheckedChangeListener(this);
    }

    @Override
    public void onResume() {
        super.onResume();
    }

    @Override
    public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
        if(isChecked) {
            EditText receivingFreqEdit = findViewById(R.id.receivingFreqEdit);
            EditText decibelDiffEdit = findViewById(R.id.decibelDiffEdit);
            EditText superpositionEdit = findViewById(R.id.superpositionEdit);

            final int RECEIVING_FREQ = Integer.parseInt(receivingFreqEdit.getText().toString());
            final double DECIBEL_DIFF = Integer.parseInt(decibelDiffEdit.getText().toString());
            final int SP_NUMBER = Integer.parseInt(superpositionEdit.getText().toString());

            spCounter = 0;
            spSpectrum = new double[SP_NUMBER][FFT_SIZE / 2];

            //実験用デバイスではRecvBufSize = 3584
            final int MIN_BUFFER__SIZE = AudioRecord.getMinBufferSize(
                    SAMPLING_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT);

            final int RECORD_BUFFER_SIZE = MIN_BUFFER__SIZE * 4;

            //最終的にFFTクラスの4096以上を確保するためbufferSizeInBytes = RecvBufSize * 4
            audioRec = new AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLING_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    RECORD_BUFFER_SIZE);

            //Vibratorクラスのインスタンス取得
            vib = (Vibrator)getSystemService(VIBRATOR_SERVICE);

            //TimeMeasureクラスのインスタンス取得
            tm = new TimeMeasure();

            audioRec.startRecording();
            isRecording = true;
            //計測開始
            tm.startMeasure();

            //フーリエ解析スレッドを生成
            fft = new Thread(new Runnable() {
                @Override
                public void run() {

                    byte[] recordData = new byte[RECORD_BUFFER_SIZE];
                    while(isRecording) {

                        audioRec.read(recordData, 0, recordData.length);

                        //エンディアン変換
                        short[] shortData = toLittleEndian(recordData, RECORD_BUFFER_SIZE);
                        //FFTクラスの作成と値の引き出し
                        double[] fftData = fastFourierTransform(shortData);
                        //パワースペクトル・デシベル値の計算
                        double[] decibelFrequencySpectrum = computePowerSpectrum(fftData);
                        //接近検知
                        detectApproach(decibelFrequencySpectrum, SP_NUMBER, RECEIVING_FREQ, DECIBEL_DIFF);
                    }
                    audioRec.stop();
                    audioRec.release();
                }

            });
            //スレッドのスタート
            fft.start();

        } else {
            if(audioRec.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                vib.cancel();
                audioRec.stop();
                //audioRec.release();
                isRecording = false;
            }
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        if(audioRec.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
            audioRec.stop();
            isRecording = false;
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if(audioRec.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
            audioRec.stop();
            audioRec.release();
            isRecording = false;
        }
    }

    /**
     * エンディアン変換
     * @param buf 受信バッファデータ
     * @param bufferSize 受信バッファサイズ
     * @return エンディアン変換後short型データ
     */
    public short[] toLittleEndian(byte[] buf, int bufferSize) {
        //エンディアン変換
        //配列bufをもとにByteBufferオブジェクトbfを作成
        ByteBuffer bf = ByteBuffer.wrap(buf);
        //バッファをクリア（データは削除されない）
        bf.clear();
        //リトルエンディアンに変更
        bf.order(ByteOrder.LITTLE_ENDIAN);
        short[] shortData = new short[bufferSize / 2];

        int bfBegin = bf.position();
        int bfEnd = bf.capacity() / 2;
        for(int i = bfBegin; i < bfEnd; i++) {
            //short値を読むための相対getメソッド
            //現在位置の2バイトを読み出す
            shortData[i] = bf.getShort();
        }
        return shortData;
    }

    /**
     * 高速フーリエ変換
     * @param shortData エンディアン変換後データ
     * @return フーリエ変換後データ
     */
    public double[] fastFourierTransform(short[] shortData) {
        //FFTクラスの作成と値の引き渡し
        FFT4g fft = new FFT4g(FFT_SIZE);
        double[] fftData = new double[FFT_SIZE];
        for(int i = 0; i < FFT_SIZE; i++) {
            fftData[i] = (double) shortData[i];
        }
        fft.rdft(1, fftData);

        return fftData;
    }

    /**
     * パワースペクトル・デシベルの計算
     * @param fftData フーリエ変換後のデータ
     * @return デシベル値
     */
    public double[] computePowerSpectrum(double[] fftData) {
        //絶対値データ absolute value
        double[] absoluteData = new double[FFT_SIZE / 2];
        //DeciBel Frequency Spectrum
        double[] decibelFrequencySpectrum = new double[FFT_SIZE / 2];
        for(int i = 0; i < FFT_SIZE; i += 2) {
            absoluteData[i / 2] = Math.sqrt(Math.pow(fftData[i], 2) + Math.pow(fftData[i + 1], 2));
            decibelFrequencySpectrum[i / 2] = (int) (20 * Math.log10(absoluteData[i / 2] / DB_BASELINE));
        }
        return decibelFrequencySpectrum;
    }

    /**
     * 接近検知
     * @param decibelFrequencySpectrum デシベル値
     * @param spNum 重ね合わせ回数
     * @param freq 受信周波数
     * @param diff 差
     */
    public void detectApproach(double[] decibelFrequencySpectrum, int spNum, int freq, double diff) {
        if(spCounter < spNum) { /* 最初の SPNUM 回は代入 */
            System.arraycopy(decibelFrequencySpectrum, 0, spSpectrum[spCounter], 0, decibelFrequencySpectrum.length);
            spCounter++;
        } else {    /* SPNUM+1 回目以降 */
            for(int i = 0; i < spNum - 1; i++) {
                System.arraycopy(spSpectrum[i + 1], 0, spSpectrum[i], 0, spSpectrum.length);
            }
            System.arraycopy(decibelFrequencySpectrum, 0, spSpectrum[spNum - 1], 0, decibelFrequencySpectrum.length);

            //計測終了
            tm.finishMeasure();
            //処理時間出力
            tm.printTimeSec();

            double[] average = calculateAverage(spSpectrum, spNum);
            double targetDecibel = computeComparisonTargetDecibelAverage(freq, average);
            giveWarning(freq, average, targetDecibel, diff);
        }
    }

    /**
     * 加算平均
     * @param spectrum 過去 SPNUM 回分のデシベル値
     * @param spNum 加算平均回数
     * @return 平均デシベル値
     */
    public double[] calculateAverage(double[][] spectrum, int spNum) {
        double[] average = new double[FFT_SIZE / 2];
        int avgFrameBegin = setFrame(16000);
        int avgFrameEnd = FFT_SIZE;

        // (SPNUM) 個の配列からトータルを計算
        for(int i = 0; i < spNum; i++) {
            for(int j = avgFrameBegin; j < avgFrameEnd; j++) {
                average[j] += spectrum[i][j];
            }
        }
        // (SPNUM) で割って平均を算出
        for(int i = avgFrameBegin; i < avgFrameEnd; i++) {
            average[i] /= spNum;
        }
        return average;
    }

    /**
     * 比較対象の周波数帯の平均デシベル値を取得
     * @param freq 受信周波数
     * @param spectrumAverage デシベル値周波数成分
     * @return 比較対象の周波数帯の平均デシベル値
     */
    public double computeComparisonTargetDecibelAverage(int freq, double[] spectrumAverage) {
        //比較対象の周波数帯の平均デシベル値を算出 Comparison Target
        double targetDecibel = 0.0;
        int frameIter = 0;
        int targetFrameBegin = setFrame(freq + 500);
        int targetFrameEnd = setFrame(freq + 700);

        for(int i = targetFrameBegin; i < targetFrameEnd; i++) {
            targetDecibel += spectrumAverage[i];
            frameIter++;
        }
        try {
            targetDecibel /= frameIter;
        } catch(ArithmeticException e) {
            e.printStackTrace();
        }
        return targetDecibel;
    }

    /**
     * 判定および警告
     * @param freq 受信周波数
     * @param spectrumAverage 平均デシベル値
     * @param target 比較対象デシベル値
     * @param diff デシベル差
     */
    public void giveWarning(int freq, double[] spectrumAverage, double target, double diff) {
        //ドップラー効果考慮 Doppler Effect
        int detectFrameBegin = setFrame(freq);
        int detectFrameEnd = setFrame(freq + 500);

        for(int i = detectFrameBegin; i < detectFrameEnd; i++) {
            if(spectrumAverage[i] > target + diff) {
                vib.cancel();
                vib.vibrate(200);
                break;
            }
        }
    }

    /**
     * 周波数からフレーム設定
     * @param freq 周波数
     * @return フレーム
     */
    public int setFrame(int freq) {
        int frame = (int)(2 * freq / RESOLUTION);
        if(frame % 2 == 1) {
            frame++;
        }
        return frame / 2;
    }
}
