package hqtest;

import battlecode.common.*;

public class Debug {
    private static RobotController rc;
    private static String activeDebugSet;
    
    public static void init(RobotController theRC, String theActiveDebugSet) {
        rc = theRC;
        activeDebugSet = theActiveDebugSet;
    }
    
    public static void indicate(String debugSet, int indicator, String message) {
        if(debugSet == activeDebugSet) {
            rc.setIndicatorString(indicator, String.format("turn %d: %s", Clock.getRoundNum(), message));
        }
    }
    
    public static void clear() {
    	rc.setIndicatorString(0, "");
    	rc.setIndicatorString(1, "");
    	rc.setIndicatorString(2, "");
    }
}
