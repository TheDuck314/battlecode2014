package first;

import battlecode.common.*;

public class RobotPlayer {
    static RobotController rc;

    public static void run(RobotController theRC) {
        rc = theRC;

        while (true) {
            try {
                switch (rc.getType()) {
                    case HQ:
                        turnHQ();
                        break;

                    case SOLDIER:
                        turnSoldier();
                        break;

                    case NOISETOWER:
                        break;

                    case PASTR:
                        break;

                    default:
                        throw new Exception("Unknown robot type :(");
                }
            } catch (Exception e) {
                e.printStackTrace();
            }

            rc.yield();
        }
    }

    private static void turnHQ() throws GameActionException {
        if (rc.isActive()) {
            System.out.println(RobotType.NOISETOWER.attackRadiusMaxSquared);
            
            if (rc.senseRobotCount() < GameConstants.MAX_ROBOTS) {
                Direction dir = Direction.NORTH;
                for (int i = 0; i < 8; i++) {
                    if (rc.canMove(dir)) {
                        rc.spawn(dir);
                        break;
                    } else {
                        dir = dir.rotateRight();
                    }
                }
            }
            
            
            
        }
    }

    private static void turnSoldier() throws GameActionException {
        if (rc.isActive() && Clock.getRoundNum() % 3 == 0) {
            
            double actionDelay = rc.getActionDelay();
            System.out.println("initialActionDelay = " + actionDelay);
            
            
            if (Math.random() < 0.005) {
                rc.construct(RobotType.PASTR);
                return;
            }

            Robot[] enemies = rc.senseNearbyGameObjects(Robot.class, 10000, rc.getTeam().opponent());
            if (enemies.length > 0) {
                RobotInfo info = rc.senseRobotInfo(enemies[0]);
                MapLocation enemyLoc = info.location;
                int dist2 = rc.getLocation().distanceSquaredTo(enemyLoc);
                if (dist2 <= RobotType.SOLDIER.attackRadiusMaxSquared) {
                    rc.attackSquare(enemyLoc);
                    return;
                }
            }

            Direction dir = Direction.values()[(int) (8 * Math.random())];
            for (int i = 0; i < 8; i++) {
                if (rc.canMove(dir)) {
                    rc.move(dir);
                    break;
                } else {
                    dir = dir.rotateRight();
                }
            }
        }
    }

}
