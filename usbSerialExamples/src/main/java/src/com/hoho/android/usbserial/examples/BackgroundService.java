package src.com.hoho.android.usbserial.examples;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.hardware.usb.UsbManager;
import android.location.Location;
import android.location.LocationManager;
import android.os.IBinder;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.util.Log;

import com.hoho.android.usbserial.driver.CdcAcmSerialDriver;
import com.hoho.android.usbserial.driver.ProbeTable;
import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.driver.UsbSerialProber;
import com.hoho.android.usbserial.examples.NMEAParser;
import com.hoho.android.usbserial.util.SerialInputOutputManager;

import java.io.IOException;
import java.util.List;

public class BackgroundService extends Service {

    private boolean mIsRunning = false;
    private volatile UsbDevice mUsbDevice = null;
    private volatile UsbDeviceConnection mUsbConnection = null;
    private NMEAParser parser = new NMEAParser();
    private MockLocationProvider mockLocationProvider;


    private final String TAG = BackgroundService.class.getSimpleName();


    private static UsbSerialPort sPort = null;

    public BackgroundService() {
    }

    private SerialInputOutputManager mSerialIoManager;

    private final SerialInputOutputManager.Listener mListener =
            new SerialInputOutputManager.Listener() {

                @Override
                public void onRunError(Exception e) {
                    Log.d(TAG, "Runner stopped.");
                }

                @Override
                public void onNewData(final byte[] data) {
                    StringBuilder result = new StringBuilder();
                    for (int i = 0; i < data.length - 2; i++) {
                        if (data[i] > ' ' && data[i] < '~') {
                            result.append(new String(new byte[]{data[i]}));
                        } else {
                            result.append(".");
                        }
                    }

                    final String message = "Read " + data.length + " bytes: \n"
                            + result.toString() + "\n\n";

                    Location loc = parser.location(result.toString());

                    Log.i(TAG, "reading GPS");

                    if (loc != null) {
                        mockLocationProvider.pushLocation(loc);
                        Log.i(TAG, loc.toString());
                    }
                }
            };

    @Override
    public IBinder onBind(Intent arg0) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
    }


    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {

        mockLocationProvider = new MockLocationProvider(LocationManager.GPS_PROVIDER, this);

        ProbeTable customTable = new ProbeTable();
        customTable.addProduct(0x1546, 0x01a7, CdcAcmSerialDriver.class);
        UsbSerialProber prober = new UsbSerialProber(customTable);

        mUsbDevice = (UsbDevice) intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);

        UsbManager mUsbManager = (UsbManager) getSystemService(Context.USB_SERVICE);

        final List<UsbSerialDriver> drivers =
                prober.findAllDrivers(mUsbManager);

        sPort = drivers.get(0).getPorts().get(0);

        UsbDeviceConnection connection = mUsbManager.openDevice(sPort.getDriver().getDevice());
        if (connection == null) {
            return Service.START_FLAG_RETRY;
        }

        try {
            sPort.open(connection);
            sPort.setParameters(9600, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE);

        } catch (IOException e) {
            Log.e(TAG, "Error setting up device: " + e.getMessage(), e);
            try {
                sPort.close();
            } catch (IOException e2) {
                // Ignore.
            }
            sPort = null;
            return Service.START_REDELIVER_INTENT;
        }

        startReceiverThread();
        return Service.START_REDELIVER_INTENT;
    }

    private void stopIoManager() {
        if (mSerialIoManager != null) {
            Log.i(TAG, "Stopping io manager ..");
            mSerialIoManager.stop();
            mSerialIoManager = null;
        }
    }

    private void startIoManager() {
        if (sPort != null) {
            Log.i(TAG, "Starting io manager ..");
            mSerialIoManager = new SerialInputOutputManager(sPort, mListener);
            mSerialIoManager.run();
        }
    }

    private void startReceiverThread() {
        new Thread("GPS_RECEIVER") {
            public void run() {
                startIoManager();
            }
        }.start();
    }
}