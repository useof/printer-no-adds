package com.noads.printer.discovery;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.noads.printer.model.Printer;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Queue;

/**
 * Finds IPP printers on the local network over mDNS/Bonjour.
 *
 * <p>Two service types are browsed: {@code _ipp._tcp} and {@code _ipps._tcp}.
 * The same physical printer usually advertises both, so results are keyed by
 * printer URI and the secure variant wins when both arrive.
 *
 * <p>Not thread-safe; drive it from the main thread. Callbacks also arrive on
 * the main thread.
 */
public final class PrinterDiscovery {

    private static final String TAG = "PrinterDiscovery";
    private static final String SERVICE_IPP = "_ipp._tcp.";
    private static final String SERVICE_IPPS = "_ipps._tcp.";
    private static final String MULTICAST_LOCK_TAG = "printer-no-ads:mdns";

    public interface Listener {
        void onPrinterFound(@NonNull Printer printer);

        void onPrinterLost(@NonNull String serviceName);

        void onDiscoveryFailed(@NonNull String message);
    }

    private final Context context;
    private final NsdManager nsdManager;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Nullable
    private WifiManager.MulticastLock multicastLock;

    private final Map<String, NsdManager.DiscoveryListener> activeListeners = new LinkedHashMap<>();

    /** serviceName -> the printer it resolved to, so "lost" events can be mapped back. */
    private final Map<String, Printer> resolved = new LinkedHashMap<>();

    /**
     * resolveService() rejects concurrent calls with FAILURE_ALREADY_ACTIVE on
     * older platforms, so resolves are queued and run one at a time.
     */
    private final Queue<NsdServiceInfo> resolveQueue = new ArrayDeque<>();
    private boolean resolveInFlight;

    private boolean running;

    @Nullable
    private Listener listener;

    public PrinterDiscovery(@NonNull Context context) {
        this.context = context.getApplicationContext();
        this.nsdManager = (NsdManager) this.context.getSystemService(Context.NSD_SERVICE);
    }

    public void setListener(@Nullable Listener listener) {
        this.listener = listener;
    }

    public boolean isRunning() {
        return running;
    }

    public void start() {
        if (running) {
            return;
        }
        if (nsdManager == null) {
            notifyFailure("Network service discovery is unavailable on this device");
            return;
        }
        running = true;
        resolved.clear();
        acquireMulticastLock();
        startBrowsing(SERVICE_IPP);
        startBrowsing(SERVICE_IPPS);
    }

    public void stop() {
        if (!running) {
            return;
        }
        running = false;
        for (Map.Entry<String, NsdManager.DiscoveryListener> entry : activeListeners.entrySet()) {
            try {
                nsdManager.stopServiceDiscovery(entry.getValue());
            } catch (IllegalArgumentException alreadyStopped) {
                Log.d(TAG, "Discovery for " + entry.getKey() + " was already stopped");
            }
        }
        activeListeners.clear();
        resolveQueue.clear();
        resolveInFlight = false;
        releaseMulticastLock();
    }

    /** Clears the found set so a pull-to-refresh restarts from scratch. */
    public void restart() {
        stop();
        start();
    }

    /* ------------------------------------------------------------------ */
    /* Browsing                                                           */
    /* ------------------------------------------------------------------ */

    private void startBrowsing(String serviceType) {
        NsdManager.DiscoveryListener discoveryListener = new NsdManager.DiscoveryListener() {
            @Override
            public void onStartDiscoveryFailed(String type, int errorCode) {
                Log.w(TAG, "Start discovery failed for " + type + ": " + errorCode);
                mainHandler.post(() -> {
                    activeListeners.remove(type);
                    if (activeListeners.isEmpty()) {
                        notifyFailure("Could not start printer discovery (error " + errorCode + ")");
                    }
                });
            }

            @Override
            public void onStopDiscoveryFailed(String type, int errorCode) {
                Log.w(TAG, "Stop discovery failed for " + type + ": " + errorCode);
            }

            @Override
            public void onDiscoveryStarted(String type) {
                Log.d(TAG, "Discovery started for " + type);
            }

            @Override
            public void onDiscoveryStopped(String type) {
                Log.d(TAG, "Discovery stopped for " + type);
            }

            @Override
            public void onServiceFound(NsdServiceInfo serviceInfo) {
                mainHandler.post(() -> enqueueResolve(serviceInfo));
            }

            @Override
            public void onServiceLost(NsdServiceInfo serviceInfo) {
                mainHandler.post(() -> {
                    String name = serviceInfo.getServiceName();
                    resolved.remove(name);
                    if (listener != null) {
                        listener.onPrinterLost(name);
                    }
                });
            }
        };

        try {
            nsdManager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, discoveryListener);
            activeListeners.put(serviceType, discoveryListener);
        } catch (IllegalArgumentException e) {
            Log.w(TAG, "Cannot browse " + serviceType, e);
        }
    }

    /* ------------------------------------------------------------------ */
    /* Resolving                                                          */
    /* ------------------------------------------------------------------ */

    private void enqueueResolve(NsdServiceInfo serviceInfo) {
        if (!running) {
            return;
        }
        resolveQueue.add(serviceInfo);
        pumpResolveQueue();
    }

    private void pumpResolveQueue() {
        if (resolveInFlight || resolveQueue.isEmpty() || !running) {
            return;
        }
        NsdServiceInfo next = resolveQueue.poll();
        resolveInFlight = true;

        nsdManager.resolveService(next, new NsdManager.ResolveListener() {
            @Override
            public void onResolveFailed(NsdServiceInfo serviceInfo, int errorCode) {
                Log.w(TAG, "Resolve failed for " + serviceInfo.getServiceName() + ": " + errorCode);
                mainHandler.post(() -> {
                    resolveInFlight = false;
                    pumpResolveQueue();
                });
            }

            @Override
            public void onServiceResolved(NsdServiceInfo serviceInfo) {
                mainHandler.post(() -> {
                    resolveInFlight = false;
                    handleResolved(serviceInfo);
                    pumpResolveQueue();
                });
            }
        });
    }

    private void handleResolved(NsdServiceInfo serviceInfo) {
        if (!running) {
            return;
        }
        InetAddress address = serviceInfo.getHost();
        if (address == null) {
            return;
        }

        String host = address.getHostAddress();
        if (host == null || host.isEmpty()) {
            return;
        }

        int port = serviceInfo.getPort();
        if (port <= 0) {
            port = Printer.DEFAULT_IPP_PORT;
        }

        boolean secure = serviceInfo.getServiceType() != null
                && serviceInfo.getServiceType().contains("_ipps");

        String resourcePath = txt(serviceInfo, "rp");
        String model = txt(serviceInfo, "ty");
        if (model == null) {
            model = txt(serviceInfo, "product");
            if (model != null) {
                // The 'product' record is wrapped in parentheses by convention.
                model = model.replaceAll("^\\((.*)\\)$", "$1");
            }
        }
        String note = txt(serviceInfo, "note");

        Printer printer = Printer.fromService(
                serviceInfo.getServiceName(),
                host,
                port,
                resourcePath,
                secure,
                model,
                note,
                false);

        Printer previous = resolved.put(serviceInfo.getServiceName(), printer);
        if (printer.equals(previous)) {
            return;
        }
        if (listener != null) {
            listener.onPrinterFound(printer);
        }
    }

    @Nullable
    private static String txt(NsdServiceInfo info, String key) {
        Map<String, byte[]> records = info.getAttributes();
        if (records == null) {
            return null;
        }
        byte[] value = records.get(key);
        if (value == null || value.length == 0) {
            return null;
        }
        String s = new String(value, StandardCharsets.UTF_8).trim();
        return s.isEmpty() ? null : s;
    }

    /* ------------------------------------------------------------------ */
    /* Multicast lock                                                     */
    /* ------------------------------------------------------------------ */

    private void acquireMulticastLock() {
        if (multicastLock != null) {
            return;
        }
        WifiManager wifi = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
        if (wifi == null) {
            return;
        }
        try {
            // Without this, many devices drop the inbound mDNS multicast and
            // discovery silently returns nothing.
            multicastLock = wifi.createMulticastLock(MULTICAST_LOCK_TAG);
            multicastLock.setReferenceCounted(true);
            multicastLock.acquire();
        } catch (SecurityException e) {
            Log.w(TAG, "Multicast lock denied; discovery may be unreliable", e);
            multicastLock = null;
        }
    }

    private void releaseMulticastLock() {
        if (multicastLock != null && multicastLock.isHeld()) {
            multicastLock.release();
        }
        multicastLock = null;
    }

    private void notifyFailure(String message) {
        if (listener != null) {
            listener.onDiscoveryFailed(message);
        }
    }
}
