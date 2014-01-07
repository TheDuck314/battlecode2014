package pathfinding;

import battlecode.common.*;

public class Nav {
    private static MapLocation dest;
    private static RobotController rc;

    private enum BugState {
        DIRECT, BUG
    }

    private enum WallSide {
        LEFT, RIGHT
    }

    private static BugState bugState;
    private static WallSide bugWallSide;
    private static int bugStartDistSq;
    private static Direction bugLastMoveDir;
    private static Direction bugLookStartDir;
    private static int bugRotationCount;

    private static boolean tryMoveDirect() throws GameActionException {
        Direction toDest = rc.getLocation().directionTo(dest);
        Direction[] tryDirs = new Direction[] { toDest, toDest.rotateLeft(), toDest.rotateRight() };
        for (Direction tryDir : tryDirs) {
            if (rc.canMove(tryDir)) {
                rc.move(tryDir);
                return true;
            }
        }
        return false;
    }

    private static void startBug() {
        bugStartDistSq = rc.getLocation().distanceSquaredTo(dest);
        bugLastMoveDir = rc.getLocation().directionTo(dest);
        bugLookStartDir = rc.getLocation().directionTo(dest);
        bugRotationCount = 0;
    }

    private static Direction findBugMoveDir() {
        Direction dir = bugLookStartDir;
        for (int i = 8; i-- > 0;) {
            if (rc.canMove(dir)) return dir;
            dir = (bugWallSide == WallSide.LEFT ? dir.rotateRight() : dir.rotateLeft());
        }
        return null;
    }

    private static int numRightRotations(Direction start, Direction end) {
        return (end.ordinal() - start.ordinal() + 8) % 8;
    }

    private static int numLeftRotations(Direction start, Direction end) {
        return (-end.ordinal() + start.ordinal() + 8) % 8;
    }

    private static int calculateBugRotation(Direction moveDir) {
        if (bugWallSide == WallSide.LEFT) {
            return numRightRotations(bugLookStartDir, moveDir) - numRightRotations(bugLookStartDir, bugLastMoveDir);
        } else {
            return numLeftRotations(bugLookStartDir, moveDir) - numLeftRotations(bugLookStartDir, bugLastMoveDir);
        }
    }

    private static void bugMove(Direction dir) throws GameActionException {
        rc.move(dir);
        bugLastMoveDir = dir;
        bugRotationCount += calculateBugRotation(dir);
        if (dir.isDiagonal()) {
            if (bugWallSide == WallSide.LEFT) bugLookStartDir = dir.rotateLeft().rotateLeft().rotateLeft();
            else bugLookStartDir = dir.rotateRight().rotateRight().rotateRight();
        } else {
            if (bugWallSide == WallSide.LEFT) bugLookStartDir = dir.rotateLeft().rotateLeft();
            else bugLookStartDir = dir.rotateRight().rotateRight();
        }
    }

    private static void bugTurn() throws GameActionException {
        Debug.indicate("bug", 0, String.format(
                "bugTurn start: wallSide = %s, startDistSq = %d, currentDistSq = %d, lastMoveDir = %s, lookStartDir = %s, rotationCount = %d",
                bugWallSide.toString(), bugStartDistSq, rc.getLocation().directionTo(dest), bugLastMoveDir.toString(), bugLookStartDir.toString(),
                bugRotationCount));
        Direction dir = findBugMoveDir();
        if (dir != null) {
            bugMove(dir);
        }
        Debug.indicate("bug", 1, String.format(
                "bugTurn end: wallSide = %s, startDistSq = %d, currentDistSq = %d, lastMoveDir = %s, lookStartDir = %s, rotationCount = %d",
                bugWallSide.toString(), bugStartDistSq, rc.getLocation().directionTo(dest), bugLastMoveDir.toString(), bugLookStartDir.toString(),
                bugRotationCount));
    }

    private static boolean canEndBug() {
        return rc.getLocation().distanceSquaredTo(dest) <= bugStartDistSq && bugRotationCount <= 0;
    }

    public static void init(RobotController theRC) {
        rc = theRC;
    }

    public static void goTo(MapLocation theDest) throws GameActionException {
        if (theDest != dest) {
            bugState = BugState.DIRECT;
            dest = theDest;
        }

        if (!rc.isActive()) return;

        // Check if we can stop bugging at the *beginning* of the turn
        if (bugState == BugState.BUG && canEndBug()) {
            bugState = BugState.DIRECT;
        }

        // If DIRECT mode, try to go directly to target
        if (bugState == BugState.DIRECT) {
            if (!tryMoveDirect()) {
                bugState = BugState.BUG;
                startBug();
            }
        }

        // If that failed, or if bugging, bug
        if (bugState == BugState.BUG) {
            bugTurn();
        }
    }
}
