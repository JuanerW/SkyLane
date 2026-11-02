/**
 * Simulates a drone delivery from a station to pickup and drop-off.
 *
 * Integration:
 * - Receives coordinates and elapsed time from the order module.
 * - A scheduled task calls calculate() every second.
 * - The tracking API sends results to the frontend.
 * - The simulator saves status changes through the order state machine.
 *
 * Inputs:
 * - Station, pickup and drop-off: {latitude, longitude}.
 * - Elapsed real seconds since departure.
 * - Simulation speed: 1 for normal speed, 10 for ten times faster.
 *
 * Output:
 * - Status, phase, position and time-based delivery progress.
 * - Flight progress for the current leg.
 * - Remaining distance, arrival time and stage time.
 * - All remaining times are measured in real seconds.
 *
 * Logic:
 * - Each leg has 60 seconds of takeoff, flight and 60 seconds of landing.
 * - Flight speed is 15 meters per second.
 * - Pickup takes 60 simulated seconds.
 * - The order completes 30 simulated seconds after arrival.
 * - Position is calculated using linear interpolation.
 *
 * This class only calculates results. It does not access the database,
 * save status changes or send tracking messages.
 */
public class DroneTracker {

    private static final double SPEED = 15.0;
    private static final double TAKEOFF_SECONDS = 60.0;
    private static final double LANDING_SECONDS = 60.0;
    private static final double PICKUP_SECONDS = 60.0;
    private static final double COMPLETION_SECONDS = 30.0;

    public static class Result {
        public final String status;
        public final String phase;

        public final double latitude;
        public final double longitude;

        // 从出发到送达的时间进度，范围为0到1
        public final double progress;

        // 0：等待出发，1：站点到取件点，2：取件点到收件点
        public final int leg;

        // 当前飞行段的空间进度，起飞时为0，降落时为1
        // 第二段开始时重新从0计算，不能当作整体配送进度
        public final double flightProgress;

        // 到当前飞行段目标点的剩余直线距离，单位为米
        // 等待出发、取件停留、到达和完成阶段返回0
        public final double remainingDistance;

        // 距离送达的真实剩余秒数，不包含送达后的30秒等待
        public final double remainingDeliverySeconds;

        // 当前起飞、飞行、降落或等待阶段的真实剩余秒数
        public final double remainingStageSeconds;

        public Result(
                String status,
                String phase,
                double[] position,
                double progress,
                int leg,
                double flightProgress,
                double remainingDistance,
                double remainingDeliverySeconds,
                double remainingStageSeconds) {

            this.status = status;
            this.phase = phase;
            this.latitude = position[0];
            this.longitude = position[1];
            this.progress = progress;
            this.leg = leg;
            this.flightProgress = flightProgress;
            this.remainingDistance = remainingDistance;
            this.remainingDeliverySeconds = remainingDeliverySeconds;
            this.remainingStageSeconds = remainingStageSeconds;
        }

        @Override
        public String toString() {
            return String.format(
                    java.util.Locale.US,
                    "status=%s, phase=%s, leg=%d, "
                            + "position=(%.6f, %.6f), progress=%.3f, "
                            + "flightProgress=%.3f, remainingDistance=%.1fm, "
                            + "remainingDeliveryTime=%.1fs, remainingStageTime=%.1fs",
                    status, phase, leg,
                    latitude, longitude, progress, flightProgress,
                    remainingDistance, remainingDeliverySeconds,
                    remainingStageSeconds
            );
        }
    }

    public static Result calculate(
            double[] station,
            double[] pickup,
            double[] dropoff,
            double elapsedSeconds,
            double simSpeed) {

        validatePoint(station);
        validatePoint(pickup);
        validatePoint(dropoff);

        if (!Double.isFinite(elapsedSeconds)
                || !Double.isFinite(simSpeed)
                || simSpeed <= 0) {
            throw new IllegalArgumentException(
                    "Elapsed time must be finite and simulation speed must be positive."
            );
        }

        double firstDistance = distance(station, pickup);
        double secondDistance = distance(pickup, dropoff);

        double firstLegTime = TAKEOFF_SECONDS
                + firstDistance / SPEED + LANDING_SECONDS;

        double secondLegTime = TAKEOFF_SECONDS
                + secondDistance / SPEED + LANDING_SECONDS;

        // 送达时间包含两段起降及取件停留，不包含最后30秒
        double totalDeliveryTime =
                firstLegTime + PICKUP_SECONDS + secondLegTime;

        // 尚未出发：出发时间固定，等待时间不再除以倍速
        if (elapsedSeconds < 0) {
            double waitSeconds = -elapsedSeconds;

            return new Result(
                    "confirmed", "waiting", station,
                    0, 0, 0, 0,
                    waitSeconds + totalDeliveryTime / simSpeed,
                    waitSeconds
            );
        }

        // 保留总经过时间，用于计算整体进度和距离送达的时间
        double simulatedElapsed = elapsedSeconds * simSpeed;

        if (!Double.isFinite(simulatedElapsed)) {
            throw new IllegalArgumentException("Simulated time is too large.");
        }

        double progress = Math.max(
                0, Math.min(simulatedElapsed / totalDeliveryTime, 1)
        );

        double remainingDeliverySeconds =
                Math.max(0, totalDeliveryTime - simulatedElapsed) / simSpeed;

        double t = simulatedElapsed;

        // 第一段：站点到取件点
        if (t < firstLegTime) {
            return calculateLeg(
                    station, pickup, firstDistance,
                    t, simSpeed, 1, "dispatched",
                    progress, remainingDeliverySeconds
            );
        }

        t -= firstLegTime;

        // 在取件点停留60个模拟秒
        if (t < PICKUP_SECONDS) {
            return new Result(
                    "picked_up", "loading", pickup,
                    progress, 1, 1, 0,
                    remainingDeliverySeconds,
                    (PICKUP_SECONDS - t) / simSpeed
            );
        }

        t -= PICKUP_SECONDS;

        // 第二段：取件点到收件点
        if (t < secondLegTime) {
            return calculateLeg(
                    pickup, dropoff, secondDistance,
                    t, simSpeed, 2, "in_transit",
                    progress, remainingDeliverySeconds
            );
        }

        t -= secondLegTime;

        // 在收件点降落后，等待30个模拟秒再完成
        if (t < COMPLETION_SECONDS) {
            return new Result(
                    "arrived", "waiting_for_completion", dropoff,
                    1, 2, 1, 0, 0,
                    (COMPLETION_SECONDS - t) / simSpeed
            );
        }

        return new Result(
                "completed", "completed", dropoff,
                1, 2, 1, 0, 0, 0
        );
    }

    /**
     * 计算一段行程中起飞、飞行或降落阶段的状态与位置。
     */
    private static Result calculateLeg(
            double[] start,
            double[] end,
            double totalDistance,
            double t,
            double simSpeed,
            int leg,
            String status,
            double progress,
            double remainingDeliverySeconds) {

        // 起飞阶段：水平位置保持在起点
        if (t < TAKEOFF_SECONDS) {
            return new Result(
                    status, "taking_off", start,
                    progress, leg, 0, totalDistance,
                    remainingDeliverySeconds,
                    (TAKEOFF_SECONDS - t) / simSpeed
            );
        }

        t -= TAKEOFF_SECONDS;

        double flightTime = totalDistance / SPEED;

        // 飞行阶段：从起点向终点移动
        // 距离为0时会跳过这个分支，避免除以0
        if (t < flightTime) {
            double traveled = SPEED * t;
            double ratio = Math.min(traveled / totalDistance, 1.0);

            double latitude =
                    start[0] + (end[0] - start[0]) * ratio;

            double longitude =
                    start[1] + (end[1] - start[1]) * ratio;

            return new Result(
                    status,
                    "flying",
                    new double[]{latitude, longitude},
                    progress,
                    leg,
                    ratio,
                    Math.max(0, totalDistance - traveled),
                    remainingDeliverySeconds,
                    (flightTime - t) / simSpeed
            );
        }

        t -= flightTime;

        // 降落阶段：水平位置保持在终点
        return new Result(
                status, "landing", end,
                progress, leg, 1, 0,
                remainingDeliverySeconds,
                Math.max(0, LANDING_SECONDS - t) / simSpeed
        );
    }

    /**
     * 使用Haversine公式计算两点间的距离，单位为米。
     */
    private static double distance(double[] a, double[] b) {
        double lat1 = Math.toRadians(a[0]);
        double lat2 = Math.toRadians(b[0]);

        double latDiff = lat2 - lat1;
        double lngDiff = Math.toRadians(b[1] - a[1]);

        double value = Math.pow(Math.sin(latDiff / 2), 2)
                + Math.cos(lat1) * Math.cos(lat2)
                * Math.pow(Math.sin(lngDiff / 2), 2);

        value = Math.max(0, Math.min(1, value));

        return 6371000 * 2 * Math.asin(Math.sqrt(value));
    }

    private static void validatePoint(double[] point) {
        if (point == null
                || point.length != 2
                || !Double.isFinite(point[0])
                || !Double.isFinite(point[1])
                || Math.abs(point[0]) > 90
                || Math.abs(point[1]) > 180) {
            throw new IllegalArgumentException(
                    "Coordinates must be valid {latitude, longitude} values."
            );
        }
    }

    public static void main(String[] args) {
        double[] station = {37.7700, -122.4200};
        double[] pickup = {37.7800, -122.4200};
        double[] dropoff = {37.7800, -122.4000};

        double[] times = {
                -10, 0, 60, 100, 150, 200,
                260, 300, 350, 450, 500, 600
        };

        for (double time : times) {
            Result result = calculate(
                    station, pickup, dropoff, time, 1
            );

            System.out.println("Elapsed " + time + "s: " + result);
        }

        // 对比倍速：位置、状态和进度应相同，剩余真实时间相差10倍
        System.out.println("Normal: "
                + calculate(station, pickup, dropoff, 100, 1));

        System.out.println("10x: "
                + calculate(station, pickup, dropoff, 10, 10));
    }
}