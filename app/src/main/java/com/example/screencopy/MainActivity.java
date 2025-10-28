package com.example.screencopy;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.ImageFormat;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.Surface;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.UUID;
import java.util.Map;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.Manifest;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "ScreenCopyApp";
    private final String DEVICE_ADDRESS = "XX:XX:XX:XX:XX:XX"; // Replace with server MAC
    private final UUID MY_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    // UI Elements
    private TextView statusTextView;
    private Button actionButton;

    // Screen Capture
    private MediaProjectionManager mProjectionManager;
    private MediaProjection mMediaProjection;
    private VirtualDisplay mVirtualDisplay;
    private ImageReader mImageReader;
    private int mWidth, mHeight, mDensity;
    private boolean isCapturing = false;
    private static final int REQUEST_CODE_SCREEN_CAPTURE = 1000;

    // Bluetooth
    private BluetoothSocket btSocket;
    private OutputStream outputStream;
    private long mLastFrameTime = 0;
    private static final long MIN_FRAME_INTERVAL = 1000 / 15;

    // Activity Result Launchers
    private final ActivityResultLauncher<Intent> screenCaptureLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                    mMediaProjection = mProjectionManager.getMediaProjection(result.getResultCode(), result.getData());
                    connectBluetoothAndStartCapture();
                } else {
                    updateStatus("Screen capture permission denied");
                    showToast("Screen capture permission denied");
                }
            });

    private final ActivityResultLauncher<String[]> permissionLauncher = registerForActivityResult(
            new ActivityResultContracts.RequestMultiplePermissions(),
            permissions -> {
                if (permissions.containsValue(false)) {
                    updateStatus("Permissions denied");
                    showToast("Required permissions were not granted");
                } else {
                    startScreenCapture();
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Initialize UI
        statusTextView = findViewById(R.id.statusTextView);
        actionButton = findViewById(R.id.connectButton);

        // Initialize MediaProjectionManager
        mProjectionManager = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        Intent serviceIntent = new Intent(this, ScreenCaptureService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        }
        // Set button click listener
        actionButton.setOnClickListener(v -> {
            if (!isCapturing) {
                startScreenCapture();
            } else {
                stopScreenCapture();
            }
        });
    }



    private void startScreenCapture() {
        Intent captureIntent = mProjectionManager.createScreenCaptureIntent();
        screenCaptureLauncher.launch(captureIntent);
    }

    private void connectBluetoothAndStartCapture() {
        new Thread(() -> {
            try {
                BluetoothAdapter btAdapter = BluetoothAdapter.getDefaultAdapter();
                if (btAdapter == null || !btAdapter.isEnabled()) {
                    runOnUiThread(() -> {
                        updateStatus("Bluetooth not available");
                        showToast("Please enable Bluetooth");
                    });
                    return;
                }

                BluetoothDevice device = btAdapter.getRemoteDevice(DEVICE_ADDRESS);
                btSocket = device.createRfcommSocketToServiceRecord(MY_UUID);
                btSocket.connect();
                outputStream = btSocket.getOutputStream();

                runOnUiThread(() -> {
                    updateStatus("Connected and capturing");
                    actionButton.setText("Stop Capture");
                    showToast("Bluetooth connected");
                });

                setupVirtualDisplay();
                isCapturing = true;

                // Start foreground service
                Intent serviceIntent = new Intent(this, ScreenCaptureService.class);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(serviceIntent);
                } else {
                    startService(serviceIntent);
                }

            } catch (Exception e) {
                Log.e(TAG, "Bluetooth connection error", e);
                runOnUiThread(() -> {
                    updateStatus("Connection failed");
                    showToast("Connection error: " + e.getMessage());
                });
            }
        }).start();
    }

    private void setupVirtualDisplay() {
        // ensure service is running (keeps your original behavior)
        Intent serviceIntent = new Intent(this, ScreenCaptureService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        }

        // Defensive: ensure media projection exists
        if (mMediaProjection == null) {
            Log.e(TAG, "setupVirtualDisplay: mMediaProjection is null");
            runOnUiThread(() -> {
                updateStatus("Projection not available");
                showToast("Projection is not initialized");
            });
            return;
        }

        // Register MediaProjection callback BEFORE creating virtual display (Android 14+ requirement)
        try {
            mMediaProjection.registerCallback(new MediaProjection.Callback() {
                @Override
                public void onStop() {
                    Log.i(TAG, "MediaProjection stopped (callback)");
                    runOnUiThread(() -> {
                        updateStatus("Projection stopped");
                        stopScreenCapture();
                    });
                }
            }, new android.os.Handler(android.os.Looper.getMainLooper()));
        } catch (Exception e) {
            // registration should not usually fail, but log it
            Log.w(TAG, "Failed to register MediaProjection callback", e);
        }

        // Get display metrics and size (unchanged)
        DisplayMetrics metrics = getResources().getDisplayMetrics();
        mDensity = metrics.densityDpi;
        Display display = getWindowManager().getDefaultDisplay();
        Point size = new Point();
        display.getRealSize(size);
        mWidth = size.x;
        mHeight = size.y;

        // Create a background HandlerThread for image reading (fixes handler null / threading races)
        android.os.HandlerThread handlerThread = new android.os.HandlerThread("ImageCaptureThread");
        handlerThread.start();
        android.os.Handler backgroundHandler = new android.os.Handler(handlerThread.getLooper());

        // ImageReader (unchanged format and buffer count)
        mImageReader = ImageReader.newInstance(mWidth, mHeight, PixelFormat.RGBA_8888, 2);

        mImageReader.setOnImageAvailableListener(reader -> {
            long now = System.currentTimeMillis();
            if (now - mLastFrameTime < MIN_FRAME_INTERVAL) {
                // skip frame but close acquired image to avoid leak
                Image skippedImage = reader.acquireLatestImage();
                if (skippedImage != null) skippedImage.close();
                return;
            }
            mLastFrameTime = now;

            Image image = null;
            try {
                image = reader.acquireLatestImage();
                if (image != null && outputStream != null) {
                    Bitmap bitmap = convertImageToBitmap(image);
                    sendImageOverBluetooth(bitmap);
                }
            } catch (Exception e) {
                Log.e(TAG, "Image processing error", e);
            } finally {
                if (image != null) {
                    image.close();
                }
            }
        }, backgroundHandler);

        // Create surface & virtual display inside try/catch to catch IllegalStateException
        Surface surface = mImageReader.getSurface();
        try {
            mVirtualDisplay = mMediaProjection.createVirtualDisplay(
                    "ScreenCapture",
                    mWidth, mHeight, mDensity,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    surface, null, null);
            Log.i(TAG, "Virtual display created: " + mWidth + "x" + mHeight + " @" + mDensity);
        } catch (IllegalStateException ise) {
            // This is the specific error seen on Android 14+ if callback wasn't registered.
            Log.e(TAG, "Failed to create virtual display (IllegalStateException)", ise);
            runOnUiThread(() -> {
                updateStatus("Capture failed");
                showToast("Capture setup failed: " + ise.getMessage());
            });
            // Clean up ImageReader and handlerThread to avoid leaks
            try {
                if (mImageReader != null) {
                    mImageReader.setOnImageAvailableListener(null, null);
                    mImageReader.close();
                }
            } catch (Exception ignore) {}

            try {
                if (handlerThread != null && handlerThread.isAlive()) {
                    handlerThread.quitSafely();
                }
            } catch (Exception ignore) {}

            // don't propagate exception further here (connectBluetoothAndStartCapture will already catch)
        } catch (Exception e) {
            Log.e(TAG, "Unexpected error creating virtual display", e);
            runOnUiThread(() -> {
                updateStatus("Capture error");
                showToast("Error creating virtual display");
            });
        }
    }

    private Bitmap convertImageToBitmap(Image image) {
        Image.Plane[] planes = image.getPlanes();
        ByteBuffer buffer = planes[0].getBuffer();
        int pixelStride = planes[0].getPixelStride();
        int rowStride = planes[0].getRowStride();
        int rowPadding = rowStride - pixelStride * mWidth;

        Bitmap bitmap = Bitmap.createBitmap(mWidth + rowPadding/pixelStride, mHeight, Bitmap.Config.ARGB_8888);
        bitmap.copyPixelsFromBuffer(buffer);
        return Bitmap.createScaledBitmap(bitmap, mWidth, mHeight, true);
    }

    private void sendImageOverBluetooth(Bitmap bitmap) {
        try {
            ByteArrayOutputStream stream = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.JPEG, 70, stream);
            byte[] byteArray = stream.toByteArray();

            // Send image size first (4 bytes)
            outputStream.write(new byte[] {
                    (byte)(byteArray.length >> 24),
                    (byte)(byteArray.length >> 16),
                    (byte)(byteArray.length >> 8),
                    (byte)byteArray.length
            });

            // Send image data
            outputStream.write(byteArray);
            outputStream.flush();
        } catch (Exception e) {
            Log.e(TAG, "Bluetooth send error", e);
            runOnUiThread(() -> updateStatus("Transmission error"));
        }
    }

    private void stopScreenCapture() {
        isCapturing = false;

        try {
            if (mVirtualDisplay != null) {
                mVirtualDisplay.release();
            }
            if (mImageReader != null) {
                mImageReader.close();
            }
            if (mMediaProjection != null) {
                mMediaProjection.stop();
            }
            if (btSocket != null) {
                btSocket.close();
            }

            stopService(new Intent(this, ScreenCaptureService.class));

            runOnUiThread(() -> {
                updateStatus("Ready to connect");
                actionButton.setText("Start Capture");
            });
        } catch (Exception e) {
            Log.e(TAG, "Stop error", e);
        }
    }

    private void updateStatus(String message) {
        statusTextView.setText("Status: " + message);
    }

    private void showToast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onDestroy() {
        stopScreenCapture();
        super.onDestroy();
    }
}