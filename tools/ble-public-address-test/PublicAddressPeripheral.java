package com.ivionics.formhr.shell;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattServer;
import android.bluetooth.BluetoothGattServerCallback;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothStatusCodes;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertisingSet;
import android.bluetooth.le.AdvertisingSetCallback;
import android.bluetooth.le.AdvertisingSetParameters;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.content.AttributionSource;
import android.content.Context;
import android.content.ContextWrapper;
import android.os.Looper;
import android.os.ParcelUuid;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A temporary ADB-shell diagnostic for testing a public BLE advertiser address.
 *
 * The regular FormHR APK cannot request a public address because Android protects
 * that setting with BLUETOOTH_PRIVILEGED. The shell UID has that permission on
 * stock user builds, so this isolates address privacy from all other variables.
 */
@SuppressLint({
    "MissingPermission",
    "HardwareIds",
    "BlockedPrivateApi",
    "PrivateApi",
    "NewApi",
    "DefaultLocale"
})
public final class PublicAddressPeripheral {
    private static final String NAME = "Polar OH1 37447F17";
    private static final int POLAR_COMPANY_ID = 0x006B;
    private static final int PUBLIC_ADDRESS = 0;
    private static final int BRIDGE_PORT = 39737;
    private static final Path ORIGINAL_NAME_FILE =
        Path.of("/data/local/tmp/formhr-public-address-original-name.txt");
    private static final byte[] POLAR_DATA = new byte[]{
        0x72, 0x06, 0x7F, 0x44, 0x37, 0x00, 0x00, 0x00, 0x33, 0x00, 72
    };

    private static final UUID HEART_RATE_SERVICE = uuid16("180D");
    private static final UUID HEART_RATE_MEASUREMENT = uuid16("2A37");
    private static final UUID BODY_SENSOR_LOCATION = uuid16("2A38");
    private static final UUID BATTERY_SERVICE = uuid16("180F");
    private static final UUID BATTERY_LEVEL = uuid16("2A19");
    private static final UUID DEVICE_INFORMATION_SERVICE = uuid16("180A");
    private static final UUID MANUFACTURER_NAME = uuid16("2A29");
    private static final UUID MODEL_NUMBER = uuid16("2A24");
    private static final UUID SERIAL_NUMBER = uuid16("2A25");
    private static final UUID FIRMWARE_REVISION = uuid16("2A26");
    private static final UUID HARDWARE_REVISION = uuid16("2A27");
    private static final UUID SOFTWARE_REVISION = uuid16("2A28");
    private static final UUID CCCD = uuid16("2902");
    private static final UUID POLAR_FILE_TRANSFER_SERVICE = uuid16("FEEE");
    private static final UUID POLAR_FILE_TRANSFER_MTU =
        UUID.fromString("fb005c51-02e7-f387-1cad-8acd2d8df0c8");
    private static final UUID POLAR_FILE_TRANSFER_DEVICE_TO_HOST =
        UUID.fromString("fb005c52-02e7-f387-1cad-8acd2d8df0c8");
    private static final UUID POLAR_FILE_TRANSFER_HOST_TO_DEVICE =
        UUID.fromString("fb005c53-02e7-f387-1cad-8acd2d8df0c8");

    private static final ArrayDeque<BluetoothGattService> pendingServices = new ArrayDeque<>();
    private static final Set<String> subscriptions = ConcurrentHashMap.newKeySet();
    private static final Set<BluetoothDevice> connectedDevices = ConcurrentHashMap.newKeySet();
    private static final Set<BluetoothDevice> heartRateSubscribers = ConcurrentHashMap.newKeySet();
    private static final Set<PrintWriter> bridgeClients = ConcurrentHashMap.newKeySet();
    private static BluetoothAdapter adapter;
    private static BluetoothGattServer server;
    private static BluetoothLeAdvertiser advertiser;
    private static AdvertisingSetCallback advertisingCallback;
    private static BluetoothGattCharacteristic measurementCharacteristic;
    private static ServerSocket bridgeServer;
    private static volatile int currentHeartRate = 72;
    private static volatile boolean advertising;
    private static String originalName;

    private PublicAddressPeripheral() {}

    public static void main(String[] args) throws Exception {
        log("process started uid=" + android.os.Process.myUid());
        if (args.length > 0 && "probe".equals(args[0])) {
            log("probe sleeping");
            Thread.sleep(60_000L);
            return;
        }
        Looper.prepareMainLooper();
        log("main looper prepared");
        initializeMainlineModules();
        log("mainline service managers initialized");
        if (args.length > 1 && "set-name".equals(args[0])) {
            BluetoothAdapter nameAdapter = BluetoothAdapter.getDefaultAdapter();
            String requestedName = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
            if (nameAdapter == null || !nameAdapter.setName(requestedName)) {
                throw new IllegalStateException("Could not set adapter name to " + requestedName);
            }
            Files.writeString(ORIGINAL_NAME_FILE, requestedName, StandardCharsets.UTF_8);
            log("adapter name set to " + requestedName);
            return;
        }
        if (args.length > 0 && "advertise-only".equals(args[0])) {
            adapter = BluetoothAdapter.getDefaultAdapter();
            if (adapter == null || !adapter.isEnabled()) {
                throw new IllegalStateException("Bluetooth is unavailable or disabled");
            }
            originalName = adapter.getName();
            log("default adapter acquired, original name=" + originalName);
            if (!NAME.equals(originalName) && !adapter.setName(NAME)) {
                throw new IllegalStateException("Could not set adapter name");
            }
            Runtime.getRuntime().addShutdownHook(new Thread(PublicAddressPeripheral::cleanUp));
            startAdvertising();
            Looper.loop();
            return;
        }
        Context context = new ShellContext();
        log("shell context created");
        BluetoothManager manager = bluetoothManager(context);
        log("BluetoothManager acquired for " + context.getAttributionSource());
        adapter = manager.getAdapter();
        if (adapter == null || !adapter.isEnabled()) {
            throw new IllegalStateException("Bluetooth is unavailable or disabled");
        }

        originalName = resolveOriginalName(adapter.getName());
        log("shell uid=" + android.os.Process.myUid() + " package=" + context.getPackageName());
        log("original adapter name=" + originalName);
        if (!NAME.equals(originalName) && !adapter.setName(NAME)) {
            throw new IllegalStateException("Could not set adapter name");
        }

        Runtime.getRuntime().addShutdownHook(new Thread(PublicAddressPeripheral::cleanUp));
        server = manager.openGattServer(context, serverCallback);
        if (server == null) throw new IllegalStateException("Could not open GATT server");
        startBridgeServer();

        pendingServices.add(createHeartRateService());
        pendingServices.add(createBatteryService());
        pendingServices.add(createDeviceInformationService());
        pendingServices.add(createPolarFileTransferService());
        addNextService();
        Looper.loop();
    }

    private static BluetoothManager bluetoothManager(Context context) throws Exception {
        Constructor<BluetoothManager> constructor =
            BluetoothManager.class.getDeclaredConstructor(Context.class);
        constructor.setAccessible(true);
        return constructor.newInstance(context);
    }

    private static void initializeMainlineModules() throws Exception {
        Class<?> activityThread = Class.forName("android.app.ActivityThread");
        Method initialize = activityThread.getDeclaredMethod("initializeMainlineModules");
        initialize.setAccessible(true);
        initialize.invoke(null);
    }

    private static void startBridgeServer() throws IOException {
        bridgeServer = new ServerSocket();
        bridgeServer.setReuseAddress(true);
        bridgeServer.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), BRIDGE_PORT));
        Thread acceptThread = new Thread(() -> {
            log("BPM bridge listening on localhost:" + BRIDGE_PORT);
            while (!bridgeServer.isClosed()) {
                try {
                    Socket socket = bridgeServer.accept();
                    Thread clientThread = new Thread(() -> handleBridgeClient(socket), "formhr-client");
                    clientThread.setDaemon(true);
                    clientThread.start();
                } catch (IOException exception) {
                    if (!bridgeServer.isClosed()) log("bridge accept failed: " + exception.getMessage());
                }
            }
        }, "formhr-bridge");
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    private static void handleBridgeClient(Socket socket) {
        PrintWriter output = null;
        try (socket;
             BufferedReader input = new BufferedReader(
                 new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8)
             )) {
            output = new PrintWriter(socket.getOutputStream(), true, StandardCharsets.UTF_8);
            bridgeClients.add(output);
            output.println("READY FormHR public-address bridge");
            writeStatus(output);
            String line;
            while ((line = input.readLine()) != null) {
                handleBridgeCommand(line.trim(), output);
            }
        } catch (IOException exception) {
            log("bridge client closed: " + exception.getMessage());
        } finally {
            if (output != null) bridgeClients.remove(output);
        }
    }

    private static void handleBridgeCommand(String line, PrintWriter output) {
        String[] parts = line.split("\\s+");
        String command = parts.length == 0 ? "" : parts[0].toUpperCase();
        try {
            switch (command) {
                case "PING" -> output.println("PONG");
                case "START" -> {
                    if (parts.length > 1) setHeartRate(Integer.parseInt(parts[1]));
                    if (advertisingCallback == null) startAdvertising();
                    output.println("OK START");
                    broadcastStatus();
                }
                case "BPM" -> {
                    if (parts.length != 2) throw new IllegalArgumentException("BPM needs one value");
                    setHeartRate(Integer.parseInt(parts[1]));
                    output.println("OK BPM " + currentHeartRate);
                }
                case "STOP" -> {
                    stopAdvertising();
                    output.println("OK STOP");
                }
                default -> output.println("ERROR unknown command");
            }
        } catch (RuntimeException exception) {
            output.println("ERROR " + exception.getMessage());
        }
    }

    private static void setHeartRate(int beatsPerMinute) {
        currentHeartRate = Math.max(30, Math.min(240, beatsPerMinute));
        byte[] packet = heartRatePacket();
        for (BluetoothDevice device : heartRateSubscribers) {
            if (server != null && measurementCharacteristic != null) {
                int result = server.notifyCharacteristicChanged(
                    device, measurementCharacteristic, false, packet
                );
                if (result != BluetoothStatusCodes.SUCCESS) {
                    log("heart-rate notify failed status=" + result);
                }
            }
        }
        log("BPM=" + currentHeartRate + " subscribers=" + heartRateSubscribers.size());
        broadcastStatus();
    }

    private static byte[] heartRatePacket() {
        return new byte[]{0x00, (byte) currentHeartRate};
    }

    private static void broadcastEvent(String event) {
        log(event);
        for (PrintWriter client : bridgeClients) client.println("EVENT " + event);
        broadcastStatus();
    }

    private static void broadcastStatus() {
        for (PrintWriter client : bridgeClients) writeStatus(client);
    }

    private static void writeStatus(PrintWriter output) {
        output.println(
            "STATUS " + (advertising ? 1 : 0) + " " + connectedDevices.size() + " " +
                heartRateSubscribers.size() + " " + currentHeartRate
        );
    }

    private static String resolveOriginalName(String currentName) throws IOException {
        if (!NAME.equals(currentName)) {
            Files.writeString(ORIGINAL_NAME_FILE, currentName, StandardCharsets.UTF_8);
            return currentName;
        }
        if (Files.exists(ORIGINAL_NAME_FILE)) {
            String savedName = Files.readString(ORIGINAL_NAME_FILE, StandardCharsets.UTF_8).trim();
            if (!savedName.isEmpty()) return savedName;
        }
        return currentName;
    }

    private static final class ShellContext extends ContextWrapper {
        private final AttributionSource source = new AttributionSource.Builder(android.os.Process.myUid())
            .setPid(android.os.Process.myPid())
            .setPackageName("com.android.shell")
            .build();

        ShellContext() {
            super(null);
        }

        @Override
        public AttributionSource getAttributionSource() {
            return source;
        }

        @Override
        public String getPackageName() {
            return "com.android.shell";
        }

        @Override
        public String getOpPackageName() {
            return "com.android.shell";
        }

        @Override
        public Looper getMainLooper() {
            return Looper.getMainLooper();
        }

        @Override
        public int getDeviceId() {
            return 0;
        }

        @Override
        public Context createDeviceContext(int deviceId) {
            return this;
        }

        @Override
        public Context getApplicationContext() {
            return this;
        }
    }

    private static void addNextService() {
        BluetoothGattService next = pendingServices.pollFirst();
        if (next == null) {
            broadcastEvent("GATT ready; waiting for watch heart rate");
        } else if (!server.addService(next)) {
            throw new IllegalStateException("Could not add service " + shortUuid(next.getUuid()));
        }
    }

    private static synchronized void startAdvertising() {
        if (advertisingCallback != null) return;
        try {
            if (!NAME.equals(adapter.getName()) && !adapter.setName(NAME)) {
                throw new IllegalStateException("Could not set adapter name");
            }
            advertiser = adapter.getBluetoothLeAdvertiser();
            if (advertiser == null) throw new IllegalStateException("BLE advertiser unavailable");

            AdvertisingSetParameters.Builder builder = new AdvertisingSetParameters.Builder()
                .setLegacyMode(true)
                .setConnectable(true)
                .setScannable(true)
                .setDiscoverable(true)
                .setInterval(AdvertisingSetParameters.INTERVAL_LOW)
                .setTxPowerLevel(AdvertisingSetParameters.TX_POWER_HIGH);

            Method setOwnAddressType = builder.getClass().getDeclaredMethod("setOwnAddressType", int.class);
            setOwnAddressType.setAccessible(true);
            setOwnAddressType.invoke(builder, PUBLIC_ADDRESS);
            log("requested ownAddressType=PUBLIC via privileged API");

            byte[] polarData = POLAR_DATA.clone();
            polarData[polarData.length - 1] = (byte) currentHeartRate;
            AdvertiseData data = new AdvertiseData.Builder()
                .setIncludeDeviceName(false)
                .setIncludeTxPowerLevel(false)
                .addServiceUuid(new ParcelUuid(HEART_RATE_SERVICE))
                .addServiceUuid(new ParcelUuid(POLAR_FILE_TRANSFER_SERVICE))
                .addManufacturerData(POLAR_COMPANY_ID, polarData)
                .build();
            AdvertiseData scanResponse = new AdvertiseData.Builder()
                .setIncludeDeviceName(true)
                .setIncludeTxPowerLevel(false)
                .build();

            advertisingCallback = new AdvertisingSetCallback() {
                @Override
                public void onAdvertisingSetStarted(AdvertisingSet set, int txPower, int status) {
                    advertising = status == ADVERTISE_SUCCESS;
                    broadcastEvent("advertising started status=" + status + " txPower=" + txPower +
                        " name=" + adapter.getName());
                }

                @Override
                public void onAdvertisingSetStopped(AdvertisingSet set) {
                    advertising = false;
                    broadcastEvent("advertising stopped");
                }
            };
            advertiser.startAdvertisingSet(
                builder.build(), data, scanResponse, null, null, advertisingCallback
            );
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Public-address API is unavailable", exception);
        }
    }

    private static synchronized void stopAdvertising() {
        AdvertisingSetCallback callback = advertisingCallback;
        advertisingCallback = null;
        advertising = false;
        if (advertiser != null && callback != null) {
            advertiser.stopAdvertisingSet(callback);
        }
        for (BluetoothDevice device : connectedDevices) {
            if (server != null) server.cancelConnection(device);
        }
        connectedDevices.clear();
        heartRateSubscribers.clear();
        subscriptions.clear();
        if (adapter != null && originalName != null && !originalName.equals(adapter.getName())) {
            adapter.setName(originalName);
        }
        broadcastStatus();
    }

    private static final BluetoothGattServerCallback serverCallback = new BluetoothGattServerCallback() {
        @Override
        public void onServiceAdded(int status, BluetoothGattService service) {
            log("service " + shortUuid(service.getUuid()) + " added status=" + status);
            if (status != BluetoothGatt.GATT_SUCCESS) {
                throw new IllegalStateException("Service registration failed: " + status);
            }
            addNextService();
        }

        @Override
        public void onConnectionStateChange(BluetoothDevice device, int status, int newState) {
            String state = newState == BluetoothProfile.STATE_CONNECTED ? "CONNECTED" :
                newState == BluetoothProfile.STATE_DISCONNECTED ? "DISCONNECTED" : String.valueOf(newState);
            if (newState == BluetoothProfile.STATE_CONNECTED) connectedDevices.add(device);
            if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                connectedDevices.remove(device);
                heartRateSubscribers.remove(device);
                subscriptions.removeIf(key -> key.startsWith(device.getAddress() + "/"));
            }
            broadcastEvent("central " + state + " status=" + status + " address=" + device.getAddress());
        }

        @Override
        public void onMtuChanged(BluetoothDevice device, int mtu) {
            broadcastEvent("MTU=" + mtu);
        }

        @Override
        public void onCharacteristicReadRequest(
            BluetoothDevice device,
            int requestId,
            int offset,
            BluetoothGattCharacteristic characteristic
        ) {
            byte[] value = valueFor(characteristic.getUuid());
            log("read " + shortUuid(characteristic.getUuid()) + " offset=" + offset);
            respondToRead(device, requestId, offset, value);
        }

        @Override
        public void onDescriptorReadRequest(
            BluetoothDevice device,
            int requestId,
            int offset,
            BluetoothGattDescriptor descriptor
        ) {
            String key = subscriptionKey(device, descriptor.getCharacteristic());
            byte[] value = subscriptions.contains(key)
                ? BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                : BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE;
            log("read CCCD " + shortUuid(descriptor.getCharacteristic().getUuid()));
            respondToRead(device, requestId, offset, value);
        }

        @Override
        public void onDescriptorWriteRequest(
            BluetoothDevice device,
            int requestId,
            BluetoothGattDescriptor descriptor,
            boolean preparedWrite,
            boolean responseNeeded,
            int offset,
            byte[] value
        ) {
            int status = BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED;
            if (CCCD.equals(descriptor.getUuid()) && offset == 0 && !preparedWrite) {
                String key = subscriptionKey(device, descriptor.getCharacteristic());
                if (Arrays.equals(value, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)) {
                    subscriptions.add(key);
                    if (HEART_RATE_MEASUREMENT.equals(descriptor.getCharacteristic().getUuid())) {
                        heartRateSubscribers.add(device);
                    }
                    status = BluetoothGatt.GATT_SUCCESS;
                } else if (Arrays.equals(value, BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE)) {
                    subscriptions.remove(key);
                    if (HEART_RATE_MEASUREMENT.equals(descriptor.getCharacteristic().getUuid())) {
                        heartRateSubscribers.remove(device);
                    }
                    status = BluetoothGatt.GATT_SUCCESS;
                } else {
                    status = BluetoothGatt.GATT_FAILURE;
                }
                broadcastEvent("write CCCD " + shortUuid(descriptor.getCharacteristic().getUuid()) +
                    " value=" + hex(value) + " status=" + status);
            }
            if (responseNeeded) server.sendResponse(device, requestId, status, offset, null);
            if (status == BluetoothGatt.GATT_SUCCESS &&
                HEART_RATE_MEASUREMENT.equals(descriptor.getCharacteristic().getUuid()) &&
                subscriptions.contains(subscriptionKey(device, descriptor.getCharacteristic()))) {
                server.notifyCharacteristicChanged(
                    device, descriptor.getCharacteristic(), false, heartRatePacket()
                );
            }
        }

        @Override
        public void onCharacteristicWriteRequest(
            BluetoothDevice device,
            int requestId,
            BluetoothGattCharacteristic characteristic,
            boolean preparedWrite,
            boolean responseNeeded,
            int offset,
            byte[] value
        ) {
            boolean pftpWrite = POLAR_FILE_TRANSFER_MTU.equals(characteristic.getUuid()) ||
                POLAR_FILE_TRANSFER_HOST_TO_DEVICE.equals(characteristic.getUuid());
            int status = pftpWrite && !preparedWrite && offset == 0
                ? BluetoothGatt.GATT_SUCCESS
                : BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED;
            log("write " + shortUuid(characteristic.getUuid()) + " value=" + hex(value) +
                " status=" + status);
            if (responseNeeded) server.sendResponse(device, requestId, status, offset, null);
        }
    };

    private static BluetoothGattService createHeartRateService() {
        BluetoothGattService service = primary(HEART_RATE_SERVICE);
        BluetoothGattCharacteristic measurement = characteristic(
            HEART_RATE_MEASUREMENT,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY | BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ
        );
        measurementCharacteristic = measurement;
        addCccd(measurement);
        service.addCharacteristic(measurement);
        service.addCharacteristic(characteristic(
            BODY_SENSOR_LOCATION,
            BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ
        ));
        return service;
    }

    private static BluetoothGattService createBatteryService() {
        BluetoothGattService service = primary(BATTERY_SERVICE);
        BluetoothGattCharacteristic battery = characteristic(
            BATTERY_LEVEL,
            BluetoothGattCharacteristic.PROPERTY_READ | BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ
        );
        addCccd(battery);
        service.addCharacteristic(battery);
        return service;
    }

    private static BluetoothGattService createDeviceInformationService() {
        BluetoothGattService service = primary(DEVICE_INFORMATION_SERVICE);
        service.addCharacteristic(readCharacteristic(MANUFACTURER_NAME));
        service.addCharacteristic(readCharacteristic(MODEL_NUMBER));
        service.addCharacteristic(readCharacteristic(SERIAL_NUMBER));
        service.addCharacteristic(readCharacteristic(FIRMWARE_REVISION));
        service.addCharacteristic(readCharacteristic(HARDWARE_REVISION));
        service.addCharacteristic(readCharacteristic(SOFTWARE_REVISION));
        return service;
    }

    private static BluetoothGattService createPolarFileTransferService() {
        BluetoothGattService service = primary(POLAR_FILE_TRANSFER_SERVICE);
        BluetoothGattCharacteristic mtu = characteristic(
            POLAR_FILE_TRANSFER_MTU,
            BluetoothGattCharacteristic.PROPERTY_WRITE |
                BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE |
                BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        );
        addCccd(mtu);
        service.addCharacteristic(mtu);
        BluetoothGattCharacteristic deviceToHost = characteristic(
            POLAR_FILE_TRANSFER_DEVICE_TO_HOST,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            0
        );
        addCccd(deviceToHost);
        service.addCharacteristic(deviceToHost);
        service.addCharacteristic(characteristic(
            POLAR_FILE_TRANSFER_HOST_TO_DEVICE,
            BluetoothGattCharacteristic.PROPERTY_WRITE | BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        ));
        return service;
    }

    private static BluetoothGattService primary(UUID uuid) {
        return new BluetoothGattService(uuid, BluetoothGattService.SERVICE_TYPE_PRIMARY);
    }

    private static BluetoothGattCharacteristic characteristic(UUID uuid, int properties, int permissions) {
        return new BluetoothGattCharacteristic(uuid, properties, permissions);
    }

    private static BluetoothGattCharacteristic readCharacteristic(UUID uuid) {
        return characteristic(uuid, BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ);
    }

    private static void addCccd(BluetoothGattCharacteristic characteristic) {
        characteristic.addDescriptor(new BluetoothGattDescriptor(
            CCCD,
            BluetoothGattDescriptor.PERMISSION_READ | BluetoothGattDescriptor.PERMISSION_WRITE
        ));
    }

    private static byte[] valueFor(UUID uuid) {
        if (HEART_RATE_MEASUREMENT.equals(uuid)) return heartRatePacket();
        if (BODY_SENSOR_LOCATION.equals(uuid)) return new byte[]{0x00};
        if (BATTERY_LEVEL.equals(uuid)) return new byte[]{100};
        if (MANUFACTURER_NAME.equals(uuid)) return text("Polar Electro Oy");
        if (MODEL_NUMBER.equals(uuid)) return text("Polar OH1");
        if (SERIAL_NUMBER.equals(uuid)) return text("37447F17");
        if (FIRMWARE_REVISION.equals(uuid)) return text("2.2.6");
        if (HARDWARE_REVISION.equals(uuid)) return text("1.0.0");
        if (SOFTWARE_REVISION.equals(uuid)) return text("2.2.6");
        return null;
    }

    private static void respondToRead(BluetoothDevice device, int requestId, int offset, byte[] value) {
        if (value == null) {
            server.sendResponse(device, requestId, BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED, offset, null);
        } else if (offset > value.length) {
            server.sendResponse(device, requestId, BluetoothGatt.GATT_INVALID_OFFSET, offset, null);
        } else {
            server.sendResponse(
                device,
                requestId,
                BluetoothGatt.GATT_SUCCESS,
                offset,
                Arrays.copyOfRange(value, offset, value.length)
            );
        }
    }

    private static String subscriptionKey(BluetoothDevice device, BluetoothGattCharacteristic characteristic) {
        return device.getAddress() + "/" + characteristic.getUuid();
    }

    private static void cleanUp() {
        try {
            stopAdvertising();
        } catch (RuntimeException ignored) {}
        try {
            if (bridgeServer != null) bridgeServer.close();
            for (PrintWriter client : bridgeClients) client.close();
            bridgeClients.clear();
        } catch (IOException ignored) {}
        try {
            if (server != null) server.close();
        } catch (RuntimeException ignored) {}
        try {
            if (adapter != null && originalName != null && !originalName.equals(adapter.getName())) {
                adapter.setName(originalName);
            }
        } catch (RuntimeException ignored) {}
        log("cleanup complete");
    }

    private static UUID uuid16(String value) {
        return UUID.fromString("0000" + value.toLowerCase() + "-0000-1000-8000-00805f9b34fb");
    }

    private static byte[] text(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static String shortUuid(UUID uuid) {
        String value = uuid.toString();
        return value.endsWith("-0000-1000-8000-00805f9b34fb")
            ? value.substring(4, 8).toUpperCase()
            : value.substring(0, 8).toUpperCase();
    }

    private static String hex(byte[] value) {
        StringBuilder result = new StringBuilder();
        for (byte b : value) {
            if (result.length() > 0) result.append(' ');
            result.append(String.format("%02X", b & 0xFF));
        }
        return result.toString();
    }

    private static void log(String message) {
        System.out.println("FORMHR-SHELL " + message);
        System.out.flush();
    }
}
