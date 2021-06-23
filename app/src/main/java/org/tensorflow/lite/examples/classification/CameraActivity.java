/*
 * Copyright 2019 The TensorFlow Authors. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.tensorflow.lite.examples.classification;

import android.Manifest;
import android.app.Fragment;
import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.Camera;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.Image.Plane;
import android.media.ImageReader;
import android.media.ImageReader.OnImageAvailableListener;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Trace;
import androidx.annotation.NonNull;
import androidx.annotation.UiThread;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import android.util.Size;
import android.view.Surface;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import com.google.android.material.bottomsheet.BottomSheetBehavior;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.tensorflow.lite.examples.classification.env.ImageUtils;
import org.tensorflow.lite.examples.classification.env.Logger;
import org.tensorflow.lite.examples.classification.tflite.Classifier.Device;
import org.tensorflow.lite.examples.classification.tflite.Classifier.Model;
import org.tensorflow.lite.examples.classification.tflite.Classifier.Recognition;

public abstract class CameraActivity extends AppCompatActivity
    implements OnImageAvailableListener,
        Camera.PreviewCallback,
        View.OnClickListener,
        AdapterView.OnItemSelectedListener {
  private static final Logger LOGGER = new Logger();

  private static final int PERMISSIONS_REQUEST = 1;

  private static final String PERMISSION_CAMERA = Manifest.permission.CAMERA;
  protected int previewWidth = 0;
  protected int previewHeight = 0;
  private Handler handler;
  private HandlerThread handlerThread;
  private boolean useCamera2API;
  private boolean isProcessingFrame = false;
  private byte[][] yuvBytes = new byte[3][];
  private int[] rgbBytes = null;
  private int yRowStride;
  private Runnable postInferenceCallback;
  private Runnable imageConverter;
  private LinearLayout bottomSheetLayout;
  private LinearLayout gestureLayout;
  private BottomSheetBehavior sheetBehavior;
  protected TextView recognitionTextView,
      recognition1TextView,
      recognition2TextView,
      recognitionValueTextView,
      recognition1ValueTextView,
      recognition2ValueTextView;
  protected TextView frameValueTextView,
      cropValueTextView,
      cameraResolutionTextView,
      rotationTextView,
      inferenceTimeTextView;
  protected ImageView bottomSheetArrowImageView;
  private ImageView plusImageView, minusImageView;
  private Spinner modelSpinner;
  private Spinner deviceSpinner;
  private TextView threadsTextView;

  // private Model model = Model.QUANTIZED;
  private Model model = Model.FLOAT;
  private Device device = Device.CPU;
  private int numThreads = -1;
  MediaPlayer mp,mp1,mp2,mp3,mp4,mp5,mp6,mp7,mp8,mp9,mp10,mp11,mp12,mp13,mp14,mp15;
  @Override
  protected void onCreate(final Bundle savedInstanceState) {
    LOGGER.d("onCreate " + this);
    super.onCreate(null);
    getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

    setContentView(R.layout.activity_camera);

    Toolbar toolbar = findViewById(R.id.toolbar);
    setSupportActionBar(toolbar);
    getSupportActionBar().setDisplayShowTitleEnabled(false);

    if (hasPermission()) {
      setFragment();
    } else {
      requestPermission();
    }

    threadsTextView = findViewById(R.id.threads);
    plusImageView = findViewById(R.id.plus);
    minusImageView = findViewById(R.id.minus);
    modelSpinner = findViewById(R.id.model_spinner);
    deviceSpinner = findViewById(R.id.device_spinner);
    bottomSheetLayout = findViewById(R.id.bottom_sheet_layout);
    gestureLayout = findViewById(R.id.gesture_layout);
    sheetBehavior = BottomSheetBehavior.from(bottomSheetLayout);
    bottomSheetArrowImageView = findViewById(R.id.bottom_sheet_arrow);

    ViewTreeObserver vto = gestureLayout.getViewTreeObserver();
    vto.addOnGlobalLayoutListener(
        new ViewTreeObserver.OnGlobalLayoutListener() {
          @Override
          public void onGlobalLayout() {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN) {
              gestureLayout.getViewTreeObserver().removeGlobalOnLayoutListener(this);
            } else {
              gestureLayout.getViewTreeObserver().removeOnGlobalLayoutListener(this);
            }
            //                int width = bottomSheetLayout.getMeasuredWidth();
            int height = gestureLayout.getMeasuredHeight();

            sheetBehavior.setPeekHeight(height);
          }
        });
    sheetBehavior.setHideable(false);

    sheetBehavior.setBottomSheetCallback(
        new BottomSheetBehavior.BottomSheetCallback() {
          @Override
          public void onStateChanged(@NonNull View bottomSheet, int newState) {
            switch (newState) {
              case BottomSheetBehavior.STATE_HIDDEN:
                break;
              case BottomSheetBehavior.STATE_EXPANDED:
                {
                  bottomSheetArrowImageView.setImageResource(R.drawable.icn_chevron_down);
                }
                break;
              case BottomSheetBehavior.STATE_COLLAPSED:
                {
                  bottomSheetArrowImageView.setImageResource(R.drawable.icn_chevron_up);
                }
                break;
              case BottomSheetBehavior.STATE_DRAGGING:
                break;
              case BottomSheetBehavior.STATE_SETTLING:
                bottomSheetArrowImageView.setImageResource(R.drawable.icn_chevron_up);
                break;
            }
          }

          @Override
          public void onSlide(@NonNull View bottomSheet, float slideOffset) {}
        });

    recognitionTextView = findViewById(R.id.detected_item);
    recognitionValueTextView = findViewById(R.id.detected_item_value);
    recognition1TextView = findViewById(R.id.detected_item1);
    recognition1ValueTextView = findViewById(R.id.detected_item1_value);
    recognition2TextView = findViewById(R.id.detected_item2);
    recognition2ValueTextView = findViewById(R.id.detected_item2_value);

    frameValueTextView = findViewById(R.id.frame_info);
    cropValueTextView = findViewById(R.id.crop_info);
    cameraResolutionTextView = findViewById(R.id.view_info);
    rotationTextView = findViewById(R.id.rotation_info);
    inferenceTimeTextView = findViewById(R.id.inference_info);

    modelSpinner.setOnItemSelectedListener(this);
    deviceSpinner.setOnItemSelectedListener(this);

    plusImageView.setOnClickListener(this);
    minusImageView.setOnClickListener(this);

    model = Model.valueOf(modelSpinner.getSelectedItem().toString().toUpperCase());
    device = Device.valueOf(deviceSpinner.getSelectedItem().toString());
    numThreads = Integer.parseInt(threadsTextView.getText().toString().trim());
  }

  protected int[] getRgbBytes() {
    imageConverter.run();
    return rgbBytes;
  }

  protected int getLuminanceStride() {
    return yRowStride;
  }

  protected byte[] getLuminance() {
    return yuvBytes[0];
  }

  /** Callback for android.hardware.Camera API */
  @Override
  public void onPreviewFrame(final byte[] bytes, final Camera camera) {
    if (isProcessingFrame) {
      LOGGER.w("Dropping frame!");
      return;
    }

    try {
      // Initialize the storage bitmaps once when the resolution is known.
      if (rgbBytes == null) {
        Camera.Size previewSize = camera.getParameters().getPreviewSize();
        previewHeight = previewSize.height;
        previewWidth = previewSize.width;
        rgbBytes = new int[previewWidth * previewHeight];
        onPreviewSizeChosen(new Size(previewSize.width, previewSize.height), 90);
      }
    } catch (final Exception e) {
      LOGGER.e(e, "Exception!");
      return;
    }

    isProcessingFrame = true;
    yuvBytes[0] = bytes;
    yRowStride = previewWidth;

    imageConverter =
        new Runnable() {
          @Override
          public void run() {
            ImageUtils.convertYUV420SPToARGB8888(bytes, previewWidth, previewHeight, rgbBytes);
          }
        };

    postInferenceCallback =
        new Runnable() {
          @Override
          public void run() {
            camera.addCallbackBuffer(bytes);
            isProcessingFrame = false;
          }
        };
    processImage();
  }

  /** Callback for Camera2 API */
  @Override
  public void onImageAvailable(final ImageReader reader) {
    // We need wait until we have some size from onPreviewSizeChosen
    if (previewWidth == 0 || previewHeight == 0) {
      return;
    }
    if (rgbBytes == null) {
      rgbBytes = new int[previewWidth * previewHeight];
    }
    try {
      final Image image = reader.acquireLatestImage();

      if (image == null) {
        return;
      }

      if (isProcessingFrame) {
        image.close();
        return;
      }
      isProcessingFrame = true;
      Trace.beginSection("imageAvailable");
      final Plane[] planes = image.getPlanes();
      fillBytes(planes, yuvBytes);
      yRowStride = planes[0].getRowStride();
      final int uvRowStride = planes[1].getRowStride();
      final int uvPixelStride = planes[1].getPixelStride();

      imageConverter =
          new Runnable() {
            @Override
            public void run() {
              ImageUtils.convertYUV420ToARGB8888(
                  yuvBytes[0],
                  yuvBytes[1],
                  yuvBytes[2],
                  previewWidth,
                  previewHeight,
                  yRowStride,
                  uvRowStride,
                  uvPixelStride,
                  rgbBytes);
            }
          };

      postInferenceCallback =
          new Runnable() {
            @Override
            public void run() {
              image.close();
              isProcessingFrame = false;
            }
          };

      processImage();
    } catch (final Exception e) {
      LOGGER.e(e, "Exception!");
      Trace.endSection();
      return;
    }
    Trace.endSection();
  }

  @Override
  public synchronized void onStart() {
    LOGGER.d("onStart " + this);
    super.onStart();
  }

  @Override
  public synchronized void onResume() {
    LOGGER.d("onResume " + this);
    super.onResume();

    handlerThread = new HandlerThread("inference");
    handlerThread.start();
    handler = new Handler(handlerThread.getLooper());

    mp = MediaPlayer.create(this, R.raw.auto);
    mp1 = MediaPlayer.create(this, R.raw.bed);
    mp2 = MediaPlayer.create(this, R.raw.botl);
    mp3 = MediaPlayer.create(this, R.raw.bus);
    mp4 = MediaPlayer.create(this,R.raw.car);
    mp5 = MediaPlayer.create(this,R.raw.cat);
    mp6 = MediaPlayer.create(this,R.raw.chair);
    mp7 = MediaPlayer.create(this,R.raw.dbin);
    mp8 = MediaPlayer.create(this,R.raw.dog);
    mp9 = MediaPlayer.create(this,R.raw.pers);
    mp10 = MediaPlayer.create(this,R.raw.stlt);
    mp11 = MediaPlayer.create(this,R.raw.table);
    mp12 = MediaPlayer.create(this,R.raw.tree);
    mp13 = MediaPlayer.create(this,R.raw.truck);
    mp14 = MediaPlayer.create(this,R.raw.tv);
    mp15 = MediaPlayer.create(this,R.raw.twhel);

 /*   mp.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
      @Override
      public void onCompletion(MediaPlayer mp) {
        mp.release();
      }
    });
    mp1.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
      @Override
      public void onCompletion(MediaPlayer mp) {
        mp.release();
      }
    });
    mp2.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
      @Override
      public void onCompletion(MediaPlayer mp) {
        mp.release();
      }
    });*/
  }

  @Override
  public synchronized void onPause() {
    LOGGER.d("onPause " + this);

    handlerThread.quitSafely();
    try {
      handlerThread.join();
      handlerThread = null;
      handler = null;
    } catch (final InterruptedException e) {
      LOGGER.e(e, "Exception!");
    }

    super.onPause();
  }

  @Override
  public synchronized void onStop() {
    LOGGER.d("onStop " + this);
    super.onStop();
  }

  @Override
  public synchronized void onDestroy() {
    LOGGER.d("onDestroy " + this);
    super.onDestroy();
  }

  protected synchronized void runInBackground(final Runnable r) {
    if (handler != null) {
      handler.post(r);
    }
  }

  @Override
  public void onRequestPermissionsResult(
      final int requestCode, final String[] permissions, final int[] grantResults) {
    if (requestCode == PERMISSIONS_REQUEST) {
      if (allPermissionsGranted(grantResults)) {
        setFragment();
      } else {
        requestPermission();
      }
    }
  }

  private static boolean allPermissionsGranted(final int[] grantResults) {
    for (int result : grantResults) {
      if (result != PackageManager.PERMISSION_GRANTED) {
        return false;
      }
    }
    return true;
  }

  private boolean hasPermission() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
      return checkSelfPermission(PERMISSION_CAMERA) == PackageManager.PERMISSION_GRANTED;
    } else {
      return true;
    }
  }

  private void requestPermission() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
      if (shouldShowRequestPermissionRationale(PERMISSION_CAMERA)) {
        Toast.makeText(
                CameraActivity.this,
                "Camera permission is required for this demo",
                Toast.LENGTH_LONG)
            .show();
      }
      requestPermissions(new String[] {PERMISSION_CAMERA}, PERMISSIONS_REQUEST);
    }
  }

  // Returns true if the device supports the required hardware level, or better.
  private boolean isHardwareLevelSupported(
      CameraCharacteristics characteristics, int requiredLevel) {
    int deviceLevel = characteristics.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL);
    if (deviceLevel == CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY) {
      return requiredLevel == deviceLevel;
    }
    // deviceLevel is not LEGACY, can use numerical sort
    return requiredLevel <= deviceLevel;
  }

  private String chooseCamera() {
    final CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
    try {
      for (final String cameraId : manager.getCameraIdList()) {
        final CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);

        // We don't use a front facing camera in this sample.
        final Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
        if (facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT) {
          continue;
        }

        final StreamConfigurationMap map =
            characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);

        if (map == null) {
          continue;
        }

        // Fallback to camera1 API for internal cameras that don't have full support.
        // This should help with legacy situations where using the camera2 API causes
        // distorted or otherwise broken previews.
        useCamera2API =
            (facing == CameraCharacteristics.LENS_FACING_EXTERNAL)
                || isHardwareLevelSupported(
                    characteristics, CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL);
        LOGGER.i("Camera API lv2?: %s", useCamera2API);
        return cameraId;
      }
    } catch (CameraAccessException e) {
      LOGGER.e(e, "Not allowed to access camera");
    }

    return null;
  }

  protected void setFragment() {
    String cameraId = chooseCamera();

    Fragment fragment;
    if (useCamera2API) {
      CameraConnectionFragment camera2Fragment =
          CameraConnectionFragment.newInstance(
              new CameraConnectionFragment.ConnectionCallback() {
                @Override
                public void onPreviewSizeChosen(final Size size, final int rotation) {
                  previewHeight = size.getHeight();
                  previewWidth = size.getWidth();
                  CameraActivity.this.onPreviewSizeChosen(size, rotation);
                }
              },
              this,
              getLayoutId(),
              getDesiredPreviewFrameSize());

      camera2Fragment.setCamera(cameraId);
      fragment = camera2Fragment;
    } else {
      fragment =
          new LegacyCameraConnectionFragment(this, getLayoutId(), getDesiredPreviewFrameSize());
    }

    getFragmentManager().beginTransaction().replace(R.id.container, fragment).commit();
  }

  protected void fillBytes(final Plane[] planes, final byte[][] yuvBytes) {
    // Because of the variable row stride it's not possible to know in
    // advance the actual necessary dimensions of the yuv planes.
    for (int i = 0; i < planes.length; ++i) {
      final ByteBuffer buffer = planes[i].getBuffer();
      if (yuvBytes[i] == null) {
        LOGGER.d("Initializing buffer %d at size %d", i, buffer.capacity());
        yuvBytes[i] = new byte[buffer.capacity()];
      }
      buffer.get(yuvBytes[i]);
    }
  }

  protected void readyForNextImage() {
    if (postInferenceCallback != null) {
      postInferenceCallback.run();
    }
  }

  protected int getScreenOrientation() {
    switch (getWindowManager().getDefaultDisplay().getRotation()) {
      case Surface.ROTATION_270:
        return 270;
      case Surface.ROTATION_180:
        return 180;
      case Surface.ROTATION_90:
        return 90;
      default:
        return 0;
    }
  }
  boolean auto = false;
  boolean bed = false;
  boolean botl = false;
  boolean bus= false;
  boolean car = false;
  boolean cat = false;
  boolean chair = false;
  boolean dbin = false;
  boolean dog = false;
  boolean pers = false;
  boolean stlt = false;
  boolean table = false;
  boolean tree = false;
  boolean truck = false;
  boolean tv = false;
  boolean twhel = false;
  @UiThread
  protected void showResultsInBottomSheet(List<Recognition> results) {

    if (results != null && results.size() >= 3) {
      Recognition recognition = results.get(0);
      if (recognition != null) {
        if (recognition.getTitle() != null) recognitionTextView.setText(recognition.getTitle());
        if (recognition.getConfidence() != null)
          recognitionValueTextView.setText(
                  String.format("%.2f", (100 * recognition.getConfidence())) + "%");
        float confi = 100 * recognition.getConfidence();
        try {
          if (!auto && recognitionTextView.getText().toString().equalsIgnoreCase("auto") && confi>66 ) {
            mp.start();
            auto =true;
            bed = false;
            botl = false;
            bus = false;
            car = false;
            cat = false;
            chair = false;
            dbin = false;
            dog = false;
            pers = false;
            stlt = false;
            table = false;
            tree = false;
            truck = false;
            tv = false;
            twhel = false;
          } else if (!bed && recognitionTextView.getText().toString().equalsIgnoreCase("bed")&& confi>66) {
            mp1.start();
            bed = true;
            auto =false;
            botl = false;
            bus = false;
            car = false;
            cat = false;
            chair = false;
            dbin = false;
            dog = false;
            pers = false;
            stlt = false;
            table = false;
            tree = false;
            truck = false;
            tv = false;
            twhel = false;
          } else if (!botl &&recognitionTextView.getText().toString().equalsIgnoreCase("bottle")&& confi>66 ) {
            mp2.start();
            botl = true;
            auto =false;
            bed = false;
            bus = false;
            car = false;
            cat = false;
            chair = false;
            dbin = false;
            dog = false;
            pers = false;
            stlt = false;
            table = false;
            tree = false;
            truck = false;
            tv = false;
            twhel = false;
          }
          else if (!bus &&recognitionTextView.getText().toString().equalsIgnoreCase("bus")&& confi>66 ) {
            mp3.start();
            bus = true;
            auto =false;
            bed = false;
            botl = false;
            car = false;
            cat = false;
            chair = false;
            dbin = false;
            dog = false;
            pers = false;
            stlt = false;
            table = false;
            tree = false;
            truck = false;
            tv = false;
            twhel = false;
          }
          else if (!car &&recognitionTextView.getText().toString().equalsIgnoreCase("car")&& confi>66 ) {
            mp4.start();
            car = true;
            bus = false;
            auto =false;
            bed = false;
            botl = false;
            cat = false;
            chair = false;
            dbin = false;
            dog = false;
            pers = false;
            stlt = false;
            table = false;
            tree = false;
            truck = false;
            tv = false;
            twhel = false;
          }
          else if (!cat &&recognitionTextView.getText().toString().equalsIgnoreCase("cat")&& confi>66 ) {
            mp5.start();
            cat = true;
            car = false;
            bus = false;
            auto =false;
            bed = false;
            botl = false;
            chair = false;
            dbin = false;
            dog = false;
            pers = false;
            stlt = false;
            table = false;
            tree = false;
            truck = false;
            tv = false;
            twhel = false;
          }
          else if (!chair &&recognitionTextView.getText().toString().equalsIgnoreCase("chair")&& confi>66 ) {
            mp6.start();
            chair = true;
            cat = false;
            car = false;
            bus = false;
            auto =false;
            bed = false;
            botl = false;
            dbin = false;
            dog = false;
            pers = false;
            stlt = false;
            table = false;
            tree = false;
            truck = false;
            tv = false;
            twhel = false;
          }
          else if (!dbin &&recognitionTextView.getText().toString().equalsIgnoreCase("dustbin")&& confi>66 ) {
            mp7.start();
            dbin = true;
            chair = false;
            cat = false;
            car = false;
            bus = false;
            auto =false;
            bed = false;
            botl = false;
            dog = false;
            pers = false;
            stlt = false;
            table = false;
            tree = false;
            truck = false;
            tv = false;
            twhel = false;
          }
          else if (!dog &&recognitionTextView.getText().toString().equalsIgnoreCase("dog")&& confi>66 ) {
            mp8.start();
            dog = true;
            dbin = false;
            chair = false;
            cat = false;
            car = false;
            bus = false;
            auto =false;
            bed = false;
            botl = false;
            pers = false;
            stlt = false;
            table = false;
            tree = false;
            truck = false;
            tv = false;
            twhel = false;
          }
          else if (!pers &&recognitionTextView.getText().toString().equalsIgnoreCase("person")&& confi>66 ) {
            mp9.start();
            pers = true;
            dog = false;
            dbin = false;
            chair = false;
            cat = false;
            car = false;
            bus = false;
            auto =false;
            bed = false;
            botl = false;
            stlt = false;
            table = false;
            tree = false;
            truck = false;
            tv = false;
            twhel = false;
          }
          else if (!stlt &&recognitionTextView.getText().toString().equalsIgnoreCase("street light")&& confi>66 ) {
            mp10.start();
            stlt = true;
            pers = false;
            dog = false;
            dbin = false;
            chair = false;
            cat = false;
            car = false;
            bus = false;
            auto =false;
            bed = false;
            botl = false;
            table = false;
            tree = false;
            truck = false;
            tv = false;
            twhel = false;
          }
          else if (!table &&recognitionTextView.getText().toString().equalsIgnoreCase("table")&& confi>66 ) {
            mp11.start();
            table = true;
            stlt = false;
            pers = false;
            dog = false;
            dbin = false;
            chair = false;
            cat = false;
            car = false;
            bus = false;
            auto =false;
            bed = false;
            botl = false;
            tree = false;
            truck = false;
            tv = false;
            twhel = false;
          }
          else if (!tree &&recognitionTextView.getText().toString().equalsIgnoreCase("tree")&& confi>66 ) {
            mp12.start();
            tree = true;
            table = false;
            stlt = false;
            pers = false;
            dog = false;
            dbin = false;
            chair = false;
            cat = false;
            car = false;
            bus = false;
            auto =false;
            bed = false;
            botl = false;
            truck = false;
            tv = false;
            twhel = false;
          }
          else if (!truck &&recognitionTextView.getText().toString().equalsIgnoreCase("truck")&& confi>66 ) {
            mp13.start();
            truck = true;
            tree = false;
            table = false;
            stlt = false;
            pers = false;
            dog = false;
            dbin = false;
            chair = false;
            cat = false;
            car = false;
            bus = false;
            auto =false;
            bed = false;
            botl = false;
            tv = false;
            twhel = false;
          }
          else if (!tv &&recognitionTextView.getText().toString().equalsIgnoreCase("television")&& confi>66 ) {
            mp14.start();
            tv = true;
            truck = false;
            tree = false;
            table = false;
            stlt = false;
            pers = false;
            dog = false;
            dbin = false;
            chair = false;
            cat = false;
            car = false;
            bus = false;
            auto =false;
            bed = false;
            botl = false;
            twhel = false;
          }
          else if (!twhel &&recognitionTextView.getText().toString().equalsIgnoreCase("two wheeler")&& confi>66 ) {
            mp15.start();
            twhel = true;
            tv = false;
            truck = false;
            tree = false;
            table = false;
            stlt = false;
            pers = false;
            dog = false;
            dbin = false;
            chair = false;
            cat = false;
            car = false;
            bus = false;
            auto =false;
            bed = false;
            botl = false;
          }

        }catch (Exception e){
          e.printStackTrace();
        }
      }

      Recognition recognition1 = results.get(1);
      if (recognition1 != null) {
        if (recognition1.getTitle() != null) recognition1TextView.setText(recognition1.getTitle());
        if (recognition1.getConfidence() != null)
          recognition1ValueTextView.setText(
              String.format("%.2f", (100 * recognition1.getConfidence())) + "%");
      }

      Recognition recognition2 = results.get(2);
      if (recognition2 != null) {
        if (recognition2.getTitle() != null) recognition2TextView.setText(recognition2.getTitle());
        if (recognition2.getConfidence() != null)
          recognition2ValueTextView.setText(
              String.format("%.2f", (100 * recognition2.getConfidence())) + "%");
      }
    }
  }

  protected void showFrameInfo(String frameInfo) {
    frameValueTextView.setText(frameInfo);
  }

  protected void showCropInfo(String cropInfo) {
    cropValueTextView.setText(cropInfo);
  }

  protected void showCameraResolution(String cameraInfo) {
    cameraResolutionTextView.setText(cameraInfo);
  }

  protected void showRotationInfo(String rotation) {
    rotationTextView.setText(rotation);
  }

  protected void showInference(String inferenceTime) {
    inferenceTimeTextView.setText(inferenceTime);
  }

  protected Model getModel() {
    return model;
  }

  private void setModel(Model model) {
    if (this.model != model) {
      LOGGER.d("Updating  model: " + model);
      this.model = model;
      onInferenceConfigurationChanged();
    }
  }

  protected Device getDevice() {
    return device;
  }

  private void setDevice(Device device) {
    if (this.device != device) {
      LOGGER.d("Updating  device: " + device);
      this.device = device;
      final boolean threadsEnabled = device == Device.CPU;
      plusImageView.setEnabled(threadsEnabled);
      minusImageView.setEnabled(threadsEnabled);
      threadsTextView.setText(threadsEnabled ? String.valueOf(numThreads) : "N/A");
      onInferenceConfigurationChanged();
    }
  }

  protected int getNumThreads() {
    return numThreads;
  }

  private void setNumThreads(int numThreads) {
    if (this.numThreads != numThreads) {
      LOGGER.d("Updating  numThreads: " + numThreads);
      this.numThreads = numThreads;
      onInferenceConfigurationChanged();
    }
  }

  protected abstract void processImage();

  protected abstract void onPreviewSizeChosen(final Size size, final int rotation);

  protected abstract int getLayoutId();

  protected abstract Size getDesiredPreviewFrameSize();

  protected abstract void onInferenceConfigurationChanged();

  @Override
  public void onClick(View v) {
    if (v.getId() == R.id.plus) {
      String threads = threadsTextView.getText().toString().trim();
      int numThreads = Integer.parseInt(threads);
      if (numThreads >= 9) return;
      setNumThreads(++numThreads);
      threadsTextView.setText(String.valueOf(numThreads));
    } else if (v.getId() == R.id.minus) {
      String threads = threadsTextView.getText().toString().trim();
      int numThreads = Integer.parseInt(threads);
      if (numThreads == 1) {
        return;
      }
      setNumThreads(--numThreads);
      threadsTextView.setText(String.valueOf(numThreads));
    }
  }

  @Override
  public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
    if (parent == modelSpinner) {
      setModel(Model.valueOf(parent.getItemAtPosition(pos).toString().toUpperCase()));
    } else if (parent == deviceSpinner) {
      setDevice(Device.valueOf(parent.getItemAtPosition(pos).toString()));
    }
  }

  @Override
  public void onNothingSelected(AdapterView<?> parent) {
    // Do nothing.
  }
}
