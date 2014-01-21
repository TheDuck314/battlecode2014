package anatid16_proxy;

import battlecode.common.*;

public class FastRandom {
    static int m_w;
    static int m_z;

    public static void init() {
    	m_w = 12987394 + 183639462 * Clock.getRoundNum(); /* must not be zero, nor 0x464fffff */
    	m_z = 87692688 + 129348760 * Clock.getRoundNum(); /* must not be zero, nor 0x9068ffff */
    }
    
    // Returns a random int in [0, Integer.MAX_VALUE]
    // Algorithm from http://en.wikipedia.org/wiki/Random_number_generation
    private static int rand() {
        m_z = 36969 * (m_z & 65535) + (m_z >> 16);
        m_w = 18000 * (m_w & 65535) + (m_w >> 16);
        //return (m_z << 16) + m_w; /* 32-bit result */
        return 0x7fffffff & ((m_z << 16) + m_w); /* positive 32-bit result */
    }

    // Returns a random integer in [0, N)
    public static int randInt(int N) {
        return rand() % N;
    }

    // Returns a random integer in [a, b)
    public static int randInt(int a, int b) {
        return a + rand() % (b-a);
    }
}