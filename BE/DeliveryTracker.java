/**
 * Simulates a robot delivery from a station to pickup and drop-off.
 *
 * Integration:
 * - Receives routes and elapsed time from the order module.
 * - A scheduled task calls calculate() every second.
 * - The tracking API sends the result to the frontend.
 * - The simulator saves status changes through the order state machine.
 *
 * Inputs:
 * - Two routes containing {latitude, longitude} points.
 * - Elapsed real seconds since departure.
 * - Simulation speed: 1 for normal speed, 10 for ten times faster.
 *
 * Output:
 * - Status, position and time-based delivery progress.
 * - Remaining distance to the current target.
 * - Remaining real seconds until arrival and until the current stage ends.
 *
 * Logic:
 * - Speed is 1.5 meters per second.
 * - Pickup takes 60 simulated seconds.
 * - The order completes 30 simulated seconds after arrival.
 * - Position is calculated from elapsed time and route distance.
 *
 * This class only calculates results. It does not access the database,
 * save status changes or send tracking messages.
 */
public class DeliveryTracker {

    private static final double SPEED = 1.5;
    private static final double PICKUP_SECONDS = 60.0;
    private static final double COMPLETION_SECONDS = 30.0;

    public static class Result {
        public final String status;
        public final double latitude;
        public final double longitude;

        // 从出发到送达的时间进度，范围为0到1
        public final double progress;

        // 当前行驶阶段到目标点的剩余路线距离，单位为米
        // 等待出发、取件停留、到达和完成阶段返回0
        public final double remainingDistance;

        // 距离送达的真实剩余秒数，不包含送达后的30秒等待
        public final double remainingDeliverySeconds;

        // 当前阶段的真实剩余秒数
        public final double remainingStageSeconds;

        public Result(
                String status,
                double[] position,
                double progress,
                double remainingDistance,
                double remainingDeliverySeconds,
                double remainingStageSeconds) {

            this.status = status;
            this.latitude = position[0];
            this.longitude = position[1];
            this.progress = progress;
            this.remainingDistance = remainingDistance;
            this.remainingDeliverySeconds = remainingDeliverySeconds;
            this.remainingStageSeconds = remainingStageSeconds;
        }

        @Override
        public String toString() {
            return String.format(
                    java.util.Locale.US,
                    "status=%s, position=(%.6f, %.6f), progress=%.3f, "
                            + "remainingDistance=%.1fm, "
                            + "remainingDeliveryTime=%.1fs, remainingStageTime=%.1fs",
                    status, latitude, longitude, progress,
                    remainingDistance, remainingDeliverySeconds,
                    remainingStageSeconds
            );
        }
    }

    public static Result calculate(
            double[][] pickupRoute,   // 站点 → 取件点
            double[][] deliveryRoute, // 取件点 → 收件点
            double elapsedSeconds,    // 当前时间减去出发时间，单位为真实秒
            double simSpeed) {        // 1为正常速度，10为十倍速

        validateRoute(pickupRoute);
        validateRoute(deliveryRoute);

        if (!Double.isFinite(elapsedSeconds)
                || !Double.isFinite(simSpeed)
                || simSpeed <= 0) {
            throw new IllegalArgumentException(
                    "Elapsed time must be finite and simulation speed must be positive."
            );
        }

        double[] pickupPoint = pickupRoute[pickupRoute.length - 1];
        double[] destination = deliveryRoute[deliveryRoute.length - 1];

        // 两条路线必须在取件点衔接，允许1米误差
        if (distance(pickupPoint, deliveryRoute[0]) > 1) {
            throw new IllegalArgumentException(
                    "The two routes must connect at the pickup point."
            );
        }

        double firstLength = routeLength(pickupRoute);
        double secondLength = routeLength(deliveryRoute);

        double firstTime = firstLength / SPEED;
        double secondTime = secondLength / SPEED;

        // 从出发到送达的模拟总时长，不包含最后30秒
        double totalDeliveryTime =
                firstTime + PICKUP_SECONDS + secondTime;

        // 尚未出发：出发时间固定，等待时间不再除以倍速
        if (elapsedSeconds < 0) {
            double waitSeconds = -elapsedSeconds;

            return new Result(
                    "confirmed",
                    pickupRoute[0],
                    0,
                    0,
                    waitSeconds + totalDeliveryTime / simSpeed,
                    waitSeconds
            );
        }

        // 保留总经过时间，不能用后面逐段减去时间后的t计算整体进度
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

        // 第一阶段：前往取件点
        if (t < firstTime) {
            double traveled = t * SPEED;

            return new Result(
                    "dispatched",
                    positionAt(pickupRoute, traveled),
                    progress,
                    Math.max(0, firstLength - traveled),
                    remainingDeliverySeconds,
                    (firstTime - t) / simSpeed
            );
        }

        t -= firstTime;

        // 第二阶段：在取件点停留60个模拟秒
        if (t < PICKUP_SECONDS) {
            return new Result(
                    "picked_up",
                    pickupPoint,
                    progress,
                    0,
                    remainingDeliverySeconds,
                    (PICKUP_SECONDS - t) / simSpeed
            );
        }

        t -= PICKUP_SECONDS;

        // 第三阶段：前往收件点
        if (t < secondTime) {
            double traveled = t * SPEED;

            return new Result(
                    "in_transit",
                    positionAt(deliveryRoute, traveled),
                    progress,
                    Math.max(0, secondLength - traveled),
                    remainingDeliverySeconds,
                    (secondTime - t) / simSpeed
            );
        }

        t -= secondTime;

        // 第四阶段：已经送达，等待30个模拟秒后完成订单
        if (t < COMPLETION_SECONDS) {
            return new Result(
                    "arrived",
                    destination,
                    1,
                    0,
                    0,
                    (COMPLETION_SECONDS - t) / simSpeed
            );
        }

        return new Result("completed", destination, 1, 0, 0, 0);
    }

    // 根据沿路线行驶的距离，找到当前位置
    private static double[] positionAt(double[][] route, double traveled) {
        for (int i = 0; i < route.length - 1; i++) {
            double[] start = route[i];
            double[] end = route[i + 1];

            double segmentLength = distance(start, end);

            // 跳过重复坐标，避免除以0
            if (segmentLength == 0) {
                continue;
            }

            if (traveled <= segmentLength) {
                double ratio = traveled / segmentLength;

                // 对城市路线中的短线段，使用线性插值
                double lat = start[0] + (end[0] - start[0]) * ratio;
                double lng = start[1] + (end[1] - start[1]) * ratio;

                return new double[]{lat, lng};
            }

            traveled -= segmentLength;
        }

        // 已走完整条路线，停在终点
        return route[route.length - 1].clone();
    }

    // 累加所有相邻坐标之间的距离
    private static double routeLength(double[][] route) {
        double total = 0;

        for (int i = 0; i < route.length - 1; i++) {
            total += distance(route[i], route[i + 1]);
        }

        return total;
    }

    // 使用Haversine公式，将两点之间的距离换算成米
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

    private static void validateRoute(double[][] route) {
        if (route == null || route.length < 2) {
            throw new IllegalArgumentException(
                    "A route must contain at least two coordinate points."
            );
        }

        for (double[] point : route) {
            if (point == null || point.length != 2
                    || !Double.isFinite(point[0])
                    || !Double.isFinite(point[1])
                    || Math.abs(point[0]) > 90
                    || Math.abs(point[1]) > 180) {
                throw new IllegalArgumentException(
                        "Coordinates must be valid {latitude, longitude} values."
                );
            }
        }
    }

    public static void main(String[] args) {
        // 假路线，仅用于验证计算，不代表真实道路
        double[][] pickupRoute = {
                {37.7700, -122.4200},
                {37.7710, -122.4200},
                {37.7710, -122.4190}
        };

        double[][] deliveryRoute = {
                {37.7710, -122.4190},
                {37.7720, -122.4190},
                {37.7720, -122.4170}
        };

        double[] times = {-10, 0, 100, 150, 200, 400, 500};

        for (double time : times) {
            Result result = calculate(
                    pickupRoute, deliveryRoute, time, 1
            );

            System.out.println("Elapsed " + time + "s: " + result);
        }

        // 对比倍速：位置、状态和进度应相同，剩余真实时间相差10倍
        System.out.println("Normal: "
                + calculate(pickupRoute, deliveryRoute, 100, 1));

        System.out.println("10x: "
                + calculate(pickupRoute, deliveryRoute, 10, 10));
    }
}