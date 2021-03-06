package com.cmccpoc.video;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.os.SystemClock;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.util.Log;
import android.util.Size;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ImageView;

import com.vanzo.demo.R;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;


/**
 * 上报图片时，选择拍照上传时显示的自定义Camera控件
 *
 * @author Yao
 */
public class Video2 extends Activity implements OnClickListener,
		TextureView.SurfaceTextureListener,
		SurfaceHolder.Callback,
		CameraOpenHelper.PreviewFrameCallback,
		MediaCodecCenter.MediaCodecCenterCallback {

	public static final String TAG = Video2.class.getSimpleName();
	private ImageView startOrStopIcon;

	private SurfaceView textureView;

	private boolean isEncoding = false;
	private boolean isRecording = false;
	private boolean isPrepare = false;
	private MediaCodecSaveControl saveControl;
	private MediaCodecCenter encodeCenter;
	private ExecutorService mExecutor;

	private static final int VIDEO_WIDTH = 1920;
	private static final int VIDEO_HEIGHT = 1080;
	private static final int VIDEO_FRAME = 20;
	private SimpleDateFormat mFormat;

	private static final int VIDEO_CUTOFF_DURATION = 60 * 1000;
	private static final int VIDEO_PRE_DURATION = 15;
	private static final int VIDEO_CUTOFF_MESSAGE = 11;
	private Timer timer;

	private CameraOpenHelper cameraOpenHelper;

	@SuppressLint("HandlerLeak")
	private Handler curHandler = new Handler() {
		@Override
		public void handleMessage(Message msg) {
			super.handleMessage(msg);
			if (msg.what == VIDEO_CUTOFF_MESSAGE) {
				cut();
			}
		}
	};

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
		requestWindowFeature(Window.FEATURE_NO_TITLE);
		setContentView(R.layout.photo_camera2);
		InitDataResource();
		requestPower();
		saveControl = new MediaCodecSaveControl(this, VIDEO_PRE_DURATION);
		encodeCenter = new MediaCodecCenter(this);
		encodeCenter.setCenterCallback(this);
		cameraOpenHelper = new CameraOpenHelper(this, textureView, new Size(VIDEO_WIDTH, VIDEO_HEIGHT));
		cameraOpenHelper.setFrameCallback(this);
	}

	private void InitDataResource() {
		Camera.CameraInfo cameraInfo = new Camera.CameraInfo();
		Camera.getCameraInfo(Camera.CameraInfo.CAMERA_FACING_BACK, cameraInfo);
		Log.w(TAG, "camera orientation " + cameraInfo.orientation);

		YuvWaterMark.init(VIDEO_WIDTH, VIDEO_HEIGHT, 0);
		startOrStopIcon = findViewById(R.id.start_or_stop_icon);
		textureView = findViewById(R.id.surface);
		textureView.getHolder().addCallback(this);
//		textureView.setSurfaceTextureListener(this);
		startOrStopIcon.setOnClickListener(this);
		startOrStopIcon.setImageResource(isRecording ? R.drawable.ic_video_session_stop : R.drawable.ic_video_session_start);
		findViewById(R.id.cut_icon).setOnClickListener(this);
		long start = SystemClock.uptimeMillis();

		YuvWaterMark.addWaterMark(2, 100, 130, "上海市闵行区秀文路898号", 20);

		long stop = SystemClock.uptimeMillis();
		Log.w("zts", "init water mark use time " + (stop - start));
		String pattern = "yyyy-MM-dd HH:mm:ss";//日期格式
		mFormat = new SimpleDateFormat(pattern, Locale.CHINA);
		timer = new Timer();
		timer.schedule(new TimerTask() {
			@Override
			public void run() {
				YuvWaterMark.addWaterMark(0, 100, 160, mFormat.format(new Date()), 20);
			}
		}, 0, 1000);
	}


	@Override
	public void onClick(View v) {
		if (v.getId() == R.id.start_or_stop_icon) {
			if (isPrepare) {
				if (isRecording) {
					isRecording = false;
					stopRecording();
				} else {
					isRecording = true;
					startRecording();
				}
			}
			startOrStopIcon.setImageResource(isRecording ? R.drawable.ic_video_session_stop : R.drawable.ic_video_session_start);
		} else if (v.getId() == R.id.cut_icon) {
			cut();
		}
	}

	@Override
	public void onDestroy() {
		super.onDestroy();
		if (cameraOpenHelper != null) {
			cameraOpenHelper.closeCamera();
			cameraOpenHelper = null;
			stopEncoder();
		}
		if (timer != null) {
			timer.cancel();
		}
		YuvWaterMark.release();
	}

	public void requestPower() {
		//判断是否已经赋予权限
		if (ContextCompat.checkSelfPermission(this,
				Manifest.permission.CAMERA)
				!= PackageManager.PERMISSION_GRANTED) {
			//如果应用之前请求过此权限但用户拒绝了请求，此方法将返回 true。
			if (!ActivityCompat.shouldShowRequestPermissionRationale(this,
					Manifest.permission.CAMERA)) {//这里可以写个对话框之类的项向用户解释为什么要申请权限，并在对话框的确认键后续再次申请权限
				//申请权限，字符串数组内是一个或多个要申请的权限，1是申请权限结果的返回参数，在onRequestPermissionsResult可以得知申请结果
				ActivityCompat.requestPermissions(this,
						new String[]{Manifest.permission.CAMERA, Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.RECORD_AUDIO}, 1);
			}
		}
	}


	private void startEncoder() {
		if (mExecutor == null) {
			mExecutor = Executors.newSingleThreadExecutor();
		}
		try {
			encodeCenter.init(VIDEO_WIDTH, VIDEO_HEIGHT, VIDEO_FRAME, VIDEO_FRAME * VIDEO_WIDTH * VIDEO_HEIGHT / 8);
		} catch (IOException e) {
			e.printStackTrace();
		}
		isEncoding = true;
		mExecutor.execute(recordRunnable);
	}

	private void stopEncoder() {
		isEncoding = false;
		curHandler.removeMessages(VIDEO_CUTOFF_MESSAGE);
	}

	private void startRecording() {
		if (saveControl != null) {
			isRecording = true;
			saveControl.startSaveVideo();
			curHandler.sendEmptyMessageDelayed(VIDEO_CUTOFF_MESSAGE, VIDEO_CUTOFF_DURATION);
		}
	}

	private void stopRecording() {
		if (saveControl != null) {
			isRecording = false;
			saveControl.stopSaveVideo();
		}
	}

	private void cut() {
		if (saveControl != null && isRecording) {
			saveControl.cutOffMediaSave();
			curHandler.sendEmptyMessageDelayed(VIDEO_CUTOFF_MESSAGE, VIDEO_CUTOFF_DURATION);
		}
	}

	private Runnable recordRunnable = new Runnable() {

		@Override
		public void run() {
			try {
				encodeCenter.startCodec();
				while (isEncoding) {
					try {
						Thread.sleep(50);
					} catch (InterruptedException e) {
						e.printStackTrace();
					}
				}
				encodeCenter.stopCodec();
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
		}
	};

	@Override
	public void onPrepare(MediaFormat audioFormat, MediaFormat videoFormat) {
		if (saveControl != null) {
			saveControl.setMediaFormat(audioFormat, videoFormat);
			isPrepare = true;
		}
//		initPPSAndSPS(videoFormat);
	}

	private void initPPSAndSPS(MediaFormat mediaFormat) {
		ByteBuffer spsbb = mediaFormat.getByteBuffer("csd-0");
		ByteBuffer ppsbb = mediaFormat.getByteBuffer("csd-1");
		byte[] pps = new byte[ppsbb.capacity() - 4];
		ppsbb.position(4);
		ppsbb.get(pps, 0, pps.length);
		byte[] sps = new byte[spsbb.capacity() - 4];
		spsbb.position(4);
		spsbb.get(sps, 0, sps.length);
		Log.w(TAG, "pps: " + Arrays.toString(pps));
		Log.w(TAG, "sps: " + Arrays.toString(sps));
	}

	private long lastVideoFrameCodedMillis = 0;

	@Override
	public void onVideoFrameCoded(ByteBuffer buffer, MediaCodec.BufferInfo info) {
		if (saveControl != null && isEncoding) {
//			Log.w(TAG, "onVideoFrameCoded " + (System.currentTimeMillis() - lastVideoFrameCodedMillis));
//			lastVideoFrameCodedMillis = System.currentTimeMillis();
			byte[] temp = new byte[info.size];
			buffer.get(temp, info.offset, info.size);
			MediaCodec.BufferInfo tempInfo = new MediaCodec.BufferInfo();
			tempInfo.set(info.offset, info.size, info.presentationTimeUs, info.flags);
			saveControl.addVideoFrameData(temp, tempInfo);
		}
	}

	private long lastAudioFrameCodedMillis = 0;

	@Override
	public void onAudioFrameCoded(ByteBuffer buffer, MediaCodec.BufferInfo info) {
		if (saveControl != null && isEncoding) {
//			Log.w(TAG, "onAudioFrameCoded " + (System.currentTimeMillis() - lastAudioFrameCodedMillis));
//			lastAudioFrameCodedMillis = System.currentTimeMillis();
			byte[] temp = new byte[info.size];
			buffer.get(temp, info.offset, info.size);
			MediaCodec.BufferInfo tempInfo = new MediaCodec.BufferInfo();
			tempInfo.set(info.offset, info.size, info.presentationTimeUs, info.flags);
			saveControl.addAudioFrameData(temp, tempInfo);
		}
	}

	private long lastPreviewFrameMillis = 0;

	private long addMarkUseMillis = 0;
	private int count = 0;

	public void onPreviewFrame(byte[] data, Camera camera) {
		if (data != null && encodeCenter != null && isEncoding) {
//			Log.w(TAG, "onPreviewFrame " + (System.currentTimeMillis() - lastPreviewFrameMillis));
//			lastPreviewFrameMillis = System.currentTimeMillis();
			addWaterMark(data);
		}
	}

	private void addWaterMark(byte[] data) {
		byte[] nv12 = new byte[data.length];
		long start = SystemClock.uptimeMillis();
		YuvWaterMark.addMark(data, nv12);
		long time = SystemClock.uptimeMillis() - start;
		addMarkUseMillis += time;
		count++;
		Log.w(TAG, "add water mark time=" + time + " ms " + addMarkUseMillis / count);
		encodeCenter.feedVideoFrameData(nv12);
	}

	@Override
	public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
		Log.w(TAG, "surfaceCreated");
		try {
			cameraOpenHelper.openCamera();
			startEncoder();
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	@Override
	public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {

	}

	@Override
	public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
		Log.w(TAG, "surfaceDestroyed");
		if (cameraOpenHelper != null) {
			cameraOpenHelper.closeCamera();
			cameraOpenHelper = null;
			stopEncoder();
		}
		return false;
	}

	@Override
	public void onSurfaceTextureUpdated(SurfaceTexture surface) {

	}

	@Override
	public void surfaceCreated(SurfaceHolder holder) {
		Log.w(TAG, "surfaceCreated");
		try {
			if (cameraOpenHelper != null) {
				cameraOpenHelper.openCamera();
				startEncoder();
			}
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	@Override
	public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {

	}

	@Override
	public void surfaceDestroyed(SurfaceHolder holder) {
		Log.w(TAG, "surfaceDestroyed");
		if (cameraOpenHelper != null) {
			cameraOpenHelper.closeCamera();
			cameraOpenHelper = null;
			stopEncoder();
		}
	}

	@Override
	public void onFrame(byte[] data) {
		Log.w(TAG, "onFrame");
		if (data != null && encodeCenter != null && isEncoding) {
//			Log.w(TAG, "onPreviewFrame " + (System.currentTimeMillis() - lastPreviewFrameMillis));
//			lastPreviewFrameMillis = System.currentTimeMillis();
			addWaterMark(data);
		}
	}
}
