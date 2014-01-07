package pathfinding;

import battlecode.common.RobotController;

public class Debug {
    private static RobotController rc;
    private static String activeDebugSet;
    
    public static void init(RobotController theRC, String theActiveDebugSet) {
        rc = theRC;
        activeDebugSet = theActiveDebugSet;
    }
    
    public static void indicate(String debugSet, int indicator, String message) {
        if(debugSet == activeDebugSet) {
            rc.setIndicatorString(indicator, message);
        }
    }
}
