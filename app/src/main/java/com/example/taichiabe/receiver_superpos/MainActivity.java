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

    int RECVFREQ;
    double DB_DIFFERENCE;
    int SPNUM;
    int FFTPOINT;

    //サンプリングレート
    int RecvSR = 44100;
    //FFTのポイント数（2の累乗）
    int fftSize = 4096;
    //デシベルベースラインの設定
    double dB_baseline = Math.pow(2, 15) * fftSize * Math.sqrt(2);
    //分解能の計算
    double resol = RecvSR / (double) fftSize;
    Vibrator vib;
    AudioRecord audioRec = null;
    boolean bIsRecording = false;
    int RecvBufSize;
    Thread fft;
    TimeMeasure tm;
    SdLog sdlog;
    //重ね合わせ回数
    int spcounter;
    double[][] spdbfs;
    double[] avgdbfs = new double[fftSize / 2];
    String filename;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        TextView RecvfreqText = findViewById(R.id.RecvfreqText);
        RecvfreqText.setText(R.string.RecvfreqText);
        Switch switch1 = findViewById(R.id.Switch);
        switch1.setOnCheckedChangeListener(this);
    }

    @Override
    public void onResume() {
        super.onResume();
    }

    @Override
    public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {

        if (isChecked) {

            EditText RecvfreqEdit = findViewById(R.id.RecvfreqEdit);
            EditText DbDifferenceEdit = findViewById(R.id.DbDifferenceEdit);
            EditText SpEdit = findViewById(R.id.SpEdit);
            RECVFREQ = Integer.parseInt(RecvfreqEdit.getText().toString());
            DB_DIFFERENCE = Integer.parseInt(DbDifferenceEdit.getText().toString());
            SPNUM = Integer.parseInt(SpEdit.getText().toString());

            filename = String.valueOf(System.currentTimeMillis());
            //SPNUMコのデシベル値配列
            spdbfs = new double[fftSize / 2][SPNUM];

            //受信周波数からFFTポイントを算出
            FFTPOINT = (int)(2 * RECVFREQ / resol);
            if(FFTPOINT % 2 == 1)
                FFTPOINT = FFTPOINT + 1;

            spcounter = 0;

            /*
            if(RECVFREQ == 18000)      FFTPOINT = 3344;
            else if(RECVFREQ == 18500) FFTPOINT = 3436;
            else if(RECVFREQ == 19000) FFTPOINT = 3530;
            else if(RECVFREQ == 19500) FFTPOINT = 3622;
            else if(RECVFREQ == 20000) FFTPOINT = 3716;
            else if(RECVFREQ == 20500) FFTPOINT = 3808;
            else if(RECVFREQ == 21000) FFTPOINT = 3900;
            else if(RECVFREQ == 21500) FFTPOINT = 3994;
            else if(RECVFREQ == 22000) FFTPOINT = 4086;
            */

            //実験用デバイスではRecvBufSize = 3584
            RecvBufSize = AudioRecord.getMinBufferSize(
                    RecvSR,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT);

            //最終的にFFTクラスの4096以上を確保するためbufferSizeInBytes = RecvBufSize * 4
            audioRec = new AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    RecvSR,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    RecvBufSize * 4);

            //Vibratorクラスのインスタンス取得
            vib = (Vibrator) getSystemService(VIBRATOR_SERVICE);

            //TimeMeasureクラスのインスタンス取得
            tm = new TimeMeasure();

            audioRec.startRecording();
            bIsRecording = true;

            //フーリエ解析スレッドを生成
            fft = new Thread(new Runnable() {
                @Override
                public void run() {

                    byte buf[] = new byte[RecvBufSize * 4];
                    while (bIsRecording) {

                        //計測開始
                        tm.start();

                        audioRec.read(buf, 0, buf.length);

                        //エンディアン変換
                        //配列bufをもとにByteBufferオブジェクトbfを作成
                        ByteBuffer bf = ByteBuffer.wrap(buf);
                        //バッファをクリア（データは削除されない）
                        bf.clear();
                        //リトルエンディアンに変更
                        bf.order(ByteOrder.LITTLE_ENDIAN);
                        short[] s = new short[RecvBufSize * 2];
                        //位置から容量まで
                        for (int i = bf.position(); i < bf.capacity() / 2; i++) {
                            //short値を読むための相対getメソッド
                            //現在位置の2バイトを読み出す
                            s[i] = bf.getShort();
                        }

                        //FFTクラスの作成と値の引き渡し
                        FFT4g fft = new FFT4g(fftSize);
                        double[] FFTdata = new double[fftSize];
                        for(int i = 0; i < fftSize; i++) {
                            FFTdata[i] = (double) s[i];
                        }
                        fft.rdft(1, FFTdata);

                        // デシベルの計算
                        double[] ps = new double[fftSize / 2];
                        double[] dbfs = new double[fftSize / 2];
                        for(int i = 0; i < fftSize; i += 2) {
                            ps[i / 2] = Math.sqrt(Math.pow(FFTdata[i], 2) + Math.pow(FFTdata[i + 1], 2));
                            dbfs[i / 2] = (int) (20 * Math.log10(ps[i / 2] / dB_baseline));
                        }
                        int i,j;

                        //重ね合わせ Superposition
                        int sp_start = (int)(2 * 16000 / resol);
                        if(sp_start % 2 == 1)
                            sp_start = sp_start + 1;

                        int sp_end = fftSize;

                        if(spcounter < SPNUM - 1) {     //最初の(SPNUM-1)回は代入

                            //spcounter回目の配列に現在のデシベル値を代入
                            for(i = sp_start / 2; i < sp_end / 2; i++) {
                                spdbfs[i][spcounter] = dbfs[i];
                            }
                            spcounter++;

                        } else {    //SPNUM回目から検知

                            //配列を1つずつずらす
                            for(i = 1; i < SPNUM; i++) {
                                for (j = sp_start / 2; j < sp_end / 2; j++) {
                                    spdbfs[j][i-1] = spdbfs[j][i];
                                }
                            }

                            //SPNUMコの配列からトータルを計算
                            for(i = sp_start / 2; i < sp_end; i++) {
                                for(j = 0; j < SPNUM; j++) {
                                    avgdbfs[i] += spdbfs[i][j];
                                }
                            }
                            //SPNUMで割って平均を算出
                            for(i = sp_start / 2; i < sp_end; i++) {
                                avgdbfs[i] /= SPNUM;
                            }

                            //比較対象の周波数帯のデシベル値を算出 comparison target
                            int ctdbfs = 0, ctcounter = 0;
                            int ct_start = (int)(2 * (RECVFREQ-1000) / resol);
                            if(ct_start % 2 == 1)
                                ct_start = ct_start + 1;

                            int ct_end = (int)(2 * (RECVFREQ-500) / resol);
                            if(ct_end % 2 == 1)
                                ct_end = ct_end + 1;

                            for(i = ct_start / 2; i < ct_end / 2; i++) {
                                ctdbfs += avgdbfs[i];
                                ctcounter++;
                            }
                            ctdbfs /= ctcounter;

                            //ドップラー効果考慮 doppler effect
                            int de_start = (int)(2 * RECVFREQ / resol);
                            if(de_start % 2 == 1)
                                de_start = de_start + 1;

                            int de_end = (int)(2 * (RECVFREQ+500) / resol);
                            if(de_end % 2 == 1)
                                de_end = de_end + 1;

                            for(i = de_start / 2; i < de_end / 2; i++) {
                                if(avgdbfs[i] >= ctdbfs + DB_DIFFERENCE) {
                                    //sdlog.put("powerspectrum3-" + filename, String.format("%.3f", i * resol) + " : " + String.valueOf(ps[i]));
                                    //検出周波数
                                    sdlog.put("freq3-" + filename, String.format("%.3f", i * resol) + " : " + String.valueOf(avgdbfs[i]));
                                    vib.cancel();
                                    vib.vibrate(200);
                                    break;
                                }
                            }

                            //計測終了
                            tm.finish();
                            sdlog.put("time3-" + filename, "processing time : " + tm.getResult().substring(0, 5));
                            //処理時間出力
                            tm.printResult();

                        }

                    }
                    audioRec.stop();
                    audioRec.release();
                }

            });
            //スレッドのスタート
            fft.start();

        } else {

            if (audioRec.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                vib.cancel();
                audioRec.stop();
                //audioRec.release();
                bIsRecording = false;
            }
        }
    }

    @Override
    public void onPause() {
        super.onPause();

        if (audioRec.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
            audioRec.stop();
            bIsRecording = false;
        }

    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        if (audioRec.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
            audioRec.stop();
            audioRec.release();
            bIsRecording = false;
        }
    }
}