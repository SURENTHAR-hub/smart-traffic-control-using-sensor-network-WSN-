import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class SmartTrafficWSN {

    // Message from sensors
    static class Message {
        final String sensorId;
        final Direction direction;
        final int vehicleCount;
        final long timestamp;

        Message(String sensorId, Direction direction, int vehicleCount) {
            this.sensorId = sensorId;
            this.direction = direction;
            this.vehicleCount = vehicleCount;
            this.timestamp = System.currentTimeMillis();
        }

        public String toString() {
            return String.format("Message[from=%s dir=%s count=%d]", sensorId, direction, vehicleCount);
        }
    }

    enum Direction { NORTH_SOUTH, EAST_WEST }

    // Sensor node (simulated)
    static class SensorNode implements Runnable {
        private final String id;
        private final Direction direction;
        private final BlockingQueue<Message> queue;
        private final Random random = new Random();
        private final int sendInterval;
        private volatile boolean running = true;

        SensorNode(String id, Direction direction, BlockingQueue<Message> queue, int sendInterval) {
            this.id = id;
            this.direction = direction;
            this.queue = queue;
            this.sendInterval = sendInterval;
        }

        public void run() {
            try {
                while (running) {
                    int count = random.nextInt(6) + (random.nextDouble() < 0.1 ? random.nextInt(15) : 0);
                    queue.put(new Message(id, direction, count));
                    Thread.sleep(sendInterval);
                }
            } catch (InterruptedException ignored) {}
        }

        void stop() { running = false; }
    }

    // Traffic light logic
    static class TrafficLight {
        private final AtomicInteger nsTime = new AtomicInteger(10);
        private final AtomicInteger ewTime = new AtomicInteger(10);
        private volatile Direction current = Direction.NORTH_SOUTH;
        private volatile boolean running = true;

        void start() {
            Thread t = new Thread(() -> {
                while (running) {
                    int duration = (current == Direction.NORTH_SOUTH) ? nsTime.get() : ewTime.get();
                    log("Light: " + current + " GREEN for " + duration + " sec");
                    try {
                        Thread.sleep(duration * 1000L);
                    } catch (InterruptedException e) {
                        break;
                    }
                    current = (current == Direction.NORTH_SOUTH) ? Direction.EAST_WEST : Direction.NORTH_SOUTH;
                }
            });
            t.setDaemon(true);
            t.start();
        }

        void setTime(Direction dir, int sec) {
            sec = Math.max(5, Math.min(sec, 60));
            if (dir == Direction.NORTH_SOUTH) nsTime.set(sec);
            else ewTime.set(sec);
        }

        int getTime(Direction dir) {
            return dir == Direction.NORTH_SOUTH ? nsTime.get() : ewTime.get();
        }

        void stop() { running = false; }
    }

    // Controller node
    static class Controller implements Runnable {
        private final BlockingQueue<Message> queue;
        private final TrafficLight light;
        private final Map<Direction, Integer> countMap = new ConcurrentHashMap<>();
        private final int evalInterval;
        private volatile boolean running = true;

        Controller(BlockingQueue<Message> queue, TrafficLight light, int evalInterval) {
            this.queue = queue;
            this.light = light;
            this.evalInterval = evalInterval;
            countMap.put(Direction.NORTH_SOUTH, 0);
            countMap.put(Direction.EAST_WEST, 0);
        }

        public void run() {
            ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
            scheduler.scheduleAtFixedRate(this::evaluate, evalInterval, evalInterval, TimeUnit.SECONDS);

            try {
                while (running) {
                    Message msg = queue.poll(1, TimeUnit.SECONDS);
                    if (msg != null) {
                        countMap.compute(msg.direction, (d, old) -> old + msg.vehicleCount);
                        log("Received " + msg);
                    }
                }
            } catch (InterruptedException ignored) {
            } finally {
                scheduler.shutdownNow();
            }
        }

        private void evaluate() {
            int ns = countMap.getOrDefault(Direction.NORTH_SOUTH, 0);
            int ew = countMap.getOrDefault(Direction.EAST_WEST, 0);
            int total = ns + ew;
            if (total == 0) return;

            int base = 10, maxExtra = 40;
            int nsNew = base + (int) ((double) ns / total * maxExtra);
            int ewNew = base + (int) ((double) ew / total * maxExtra);

            light.setTime(Direction.NORTH_SOUTH, nsNew);
            light.setTime(Direction.EAST_WEST, ewNew);
            log(String.format("Adjusted timings: NS=%ds, EW=%ds", nsNew, ewNew));

            countMap.put(Direction.NORTH_SOUTH, 0);
            countMap.put(Direction.EAST_WEST, 0);
        }

        void stop() { running = false; }
    }

    // Helper
    static synchronized void log(String s) {
        System.out.printf("[%1$tT] %2$s%n", new Date(), s);
    }

    public static void main(String[] args) throws Exception {
        log("Starting Smart Traffic WSN simulation...");

        BlockingQueue<Message> queue = new LinkedBlockingQueue<>(1000);
        TrafficLight light = new TrafficLight();
        Controller controller = new Controller(queue, light, 8);

        Thread controllerThread = new Thread(controller);
        controllerThread.setDaemon(true);
        controllerThread.start();
        light.start();

        List<SensorNode> sensors = List.of(
                new SensorNode("S1", Direction.NORTH_SOUTH, queue, 2000),
                new SensorNode("S2", Direction.EAST_WEST, queue, 2500)
        );

        List<Thread> sensorThreads = new ArrayList<>();
        for (SensorNode s : sensors) {
            Thread t = new Thread(s);
            t.setDaemon(true);
            t.start();
            sensorThreads.add(t);
        }

        int simTime = 60;
        for (int i = 0; i < simTime; i++) Thread.sleep(1000);

        log("Stopping simulation...");
        sensors.forEach(SensorNode::stop);
        controller.stop();
        light.stop();
        log("Simulation ended.");
    }
}
