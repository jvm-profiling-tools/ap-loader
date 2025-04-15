// a main class that just waste some cpu for two seconds

public class TestMain {
    public static void main(String[] args) throws Exception {
        long start = System.currentTimeMillis();
        int i = 0;
        while (System.currentTimeMillis() - start < 2000) {
            i = Math.pow(i + 1, 2) > 1000000 ? 0 : i + 1;
        }
    }
}