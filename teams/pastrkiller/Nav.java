package pastrkiller;

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
	private static WallSide bugWallSide = WallSide.LEFT;
	private static int bugStartDistSq;
	private static Direction bugLastMoveDir;
	private static Direction bugLookStartDir;
	private static int bugRotationCount;

	private static String actions;

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
		bugRotationCount += calculateBugRotation(dir);
		bugLastMoveDir = dir;
		if (bugWallSide == WallSide.LEFT) bugLookStartDir = dir.rotateLeft().rotateLeft();
		else bugLookStartDir = dir.rotateRight().rotateRight();
	}

	private static boolean detectBugIntoEdge() {
		if (rc.senseTerrainTile(rc.getLocation().add(bugLastMoveDir)) != TerrainTile.OFF_MAP) return false;

		if (bugLastMoveDir.isDiagonal()) {
			if (bugWallSide == WallSide.LEFT) {
				return !rc.canMove(bugLastMoveDir.rotateLeft());
			} else {
				return !rc.canMove(bugLastMoveDir.rotateRight());
			}
		} else {
			return true;
		}
	}

	private static void reverseBugWallFollowDir() {
		bugWallSide = (bugWallSide == WallSide.LEFT ? WallSide.RIGHT : WallSide.LEFT);
		startBug();
	}

	private static void bugTurn() throws GameActionException {
		if (bugWallSide != null) Debug.indicate("bug", 0, String.format(
				"bugTurn start: wallSide = %s, startDistSq = %d, currentDistSq = %d, lastMoveDir = %s, lookStartDir = %s, rotationCount = %d",
				bugWallSide.toString(), bugStartDistSq, rc.getLocation().distanceSquaredTo(dest), bugLastMoveDir.toString(), bugLookStartDir.toString(),
				bugRotationCount));
		if (detectBugIntoEdge()) {
			actions += " reversed on hitting edge;";
			reverseBugWallFollowDir();
		}
		Direction dir = findBugMoveDir();
		if (dir != null) {
			actions += " bugMove;";
			bugMove(dir);
		}
		Debug.indicate("bug", 1, String.format(
				"bugTurn end: wallSide = %s, startDistSq = %d, currentDistSq = %d, lastMoveDir = %s, lookStartDir = %s, rotationCount = %d",
				bugWallSide.toString(), bugStartDistSq, rc.getLocation().distanceSquaredTo(dest), bugLastMoveDir.toString(), bugLookStartDir.toString(),
				bugRotationCount));
	}

	private static boolean canEndBug() {
		return (bugRotationCount <= 0 || bugRotationCount >= 8) && rc.getLocation().distanceSquaredTo(dest) <= bugStartDistSq;
	}

	private static void bugTo(MapLocation theDest) throws GameActionException {
		actions = "";

		// Check if we can stop bugging at the *beginning* of the turn
		if (bugState == BugState.BUG && canEndBug()) {
			actions += " ended bugging;";
			bugState = BugState.DIRECT;
		}

		// If DIRECT mode, try to go directly to target
		if (bugState == BugState.DIRECT) {
			actions += " tried direct move;";
			if (!tryMoveDirect()) {
				actions += " started bugging;";
				bugState = BugState.BUG;
				startBug();
			}
		}

		// If that failed, or if bugging, bug
		if (bugState == BugState.BUG) {
			actions += "bugTurn";
			bugTurn();
		}

		Debug.indicate("bug", 2, "bug actions: " + actions);
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

		if (rc.getLocation().equals(dest)) return;

		bugTo(theDest);
	}
}
