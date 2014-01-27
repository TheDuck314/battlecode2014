package zephyr;

import battlecode.common.*;

public class Nav {
	private static MapLocation dest;
	private static RobotController rc;
	private static boolean sneak = false;
	private static boolean engage = false;
	private static int[] numEnemiesAttackingMoveDirs;
	private static boolean fightDecisionIsCached = false;
	private static boolean fightIsWinningDecision;

	private enum BugState {
		DIRECT,
		BUG
	}

	private enum WallSide {
		LEFT,
		RIGHT
	}

	private static BugState bugState;
	private static WallSide bugWallSide = WallSide.LEFT;
	private static int bugStartDistSq;
	private static Direction bugLastMoveDir;
	private static Direction bugLookStartDir;
	private static int bugRotationCount;
	private static int bugMovesSinceSeenObstacle = 0;

	private static boolean tryMoveDirect() throws GameActionException {
		MapLocation here = rc.getLocation();
		Direction toDest = here.directionTo(dest);
		Direction bestDir = null;
		Direction[] dirs = new Direction[3];
		dirs[0] = toDest;
		Direction dirLeft = toDest.rotateLeft();
		Direction dirRight = toDest.rotateRight();
		if (here.add(dirLeft).distanceSquaredTo(dest) < here.add(dirRight).distanceSquaredTo(dest)) {
			dirs[1] = dirLeft;
			dirs[2] = dirRight;
		} else {
			dirs[1] = dirRight;
			dirs[2] = dirLeft;
		}
		for (Direction dir : dirs) {
			if (bestDir != null) {
				if (rc.senseTerrainTile(here.add(dir)) != TerrainTile.ROAD) continue; // only bother with suboptimal directions if they have roads
			}
			if (canMoveSafely(dir)) {
				if (moveIsAllowedByEngagementRules(dir)) {
					if (rc.senseTerrainTile(here.add(dir)) == TerrainTile.ROAD) { // if we found a road, go there immediately
						move(dir);
						return true;
					}
					bestDir = dir;
				}
			}
		}
		if (bestDir != null) {
			move(bestDir);
			return true;
		}
		return false;
	}

	private static void startBug() throws GameActionException {
		bugStartDistSq = rc.getLocation().distanceSquaredTo(dest);
		bugLastMoveDir = rc.getLocation().directionTo(dest);
		bugLookStartDir = rc.getLocation().directionTo(dest);
		bugRotationCount = 0;
		bugMovesSinceSeenObstacle = 0;

		// try to intelligently choose on which side we will keep the wall
		Direction leftTryDir = bugLastMoveDir.rotateLeft();
		for (int i = 0; i < 3; i++) {
			if (!canMoveSafely(leftTryDir) || !moveIsAllowedByEngagementRules(leftTryDir)) leftTryDir = leftTryDir.rotateLeft();
			else break;
		}
		Direction rightTryDir = bugLastMoveDir.rotateRight();
		for (int i = 0; i < 3; i++) {
			if (!canMoveSafely(rightTryDir) || !moveIsAllowedByEngagementRules(rightTryDir)) rightTryDir = rightTryDir.rotateRight();
			else break;
		}
		if (dest.distanceSquaredTo(rc.getLocation().add(leftTryDir)) < dest.distanceSquaredTo(rc.getLocation().add(rightTryDir))) {
			bugWallSide = WallSide.RIGHT;
		} else {
			bugWallSide = WallSide.LEFT;
		}
	}

	private static Direction findBugMoveDir() throws GameActionException {
		bugMovesSinceSeenObstacle++;
		Direction dir = bugLookStartDir;
		for (int i = 8; i-- > 0;) {
			if (canMoveSafely(dir) && moveIsAllowedByEngagementRules(dir)) return dir;
			dir = (bugWallSide == WallSide.LEFT ? dir.rotateRight() : dir.rotateLeft());
			bugMovesSinceSeenObstacle = 0;
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
		move(dir);
		bugRotationCount += calculateBugRotation(dir);
		bugLastMoveDir = dir;
		// if (bugWallSide == WallSide.LEFT) bugLookStartDir = dir.isDiagonal() ? dir.rotateLeft().rotateLeft() : dir.rotateLeft();
		// else bugLookStartDir = dir.isDiagonal() ? dir.rotateRight().rotateRight() : dir.rotateRight();
		if (bugWallSide == WallSide.LEFT) bugLookStartDir = dir.rotateLeft().rotateLeft();
		else bugLookStartDir = dir.rotateRight().rotateRight();
	}

	private static boolean detectBugIntoEdge() {
		if (rc.senseTerrainTile(rc.getLocation().add(bugLastMoveDir)) != TerrainTile.OFF_MAP) return false;

		if (bugLastMoveDir.isDiagonal()) {
			if (bugWallSide == WallSide.LEFT) {
				return !canMoveSafely(bugLastMoveDir.rotateLeft());
			} else {
				return !canMoveSafely(bugLastMoveDir.rotateRight());
			}
		} else {
			return true;
		}
	}

	private static void reverseBugWallFollowDir() throws GameActionException {
		bugWallSide = (bugWallSide == WallSide.LEFT ? WallSide.RIGHT : WallSide.LEFT);
		startBug();
	}

	private static void bugTurn() throws GameActionException {
		if (detectBugIntoEdge()) {
			reverseBugWallFollowDir();
		}
		Direction dir = findBugMoveDir();
		if (dir != null) {
			bugMove(dir);
		}
	}

	private static boolean canEndBug() {
		if (bugMovesSinceSeenObstacle >= 4) return true;
		return (bugRotationCount <= 0 || bugRotationCount >= 8) && rc.getLocation().distanceSquaredTo(dest) <= bugStartDistSq;
	}

	private static void bugTo(MapLocation theDest) throws GameActionException {
		// Check if we can stop bugging at the *beginning* of the turn
		if (bugState == BugState.BUG) {
			if (canEndBug()) {
				// Debug.indicateAppend("nav", 1, "ending bug; ");
				bugState = BugState.DIRECT;
			}
		}

		// If DIRECT mode, try to go directly to target
		if (bugState == BugState.DIRECT) {
			if (!tryMoveDirect()) {
				// Debug.indicateAppend("nav", 1, "starting to bug; ");
				bugState = BugState.BUG;
				startBug();
			} else {
				// Debug.indicateAppend("nav", 1, "successful direct move; ");
			}
		}

		// If that failed, or if bugging, bug
		if (bugState == BugState.BUG) {
			// Debug.indicateAppend("nav", 1, "bugging; ");
			bugTurn();
		}
	}

	private static boolean tryMoveBfs(MapLocation here) throws GameActionException {
		Direction bfsDir = Bfs.readResult(here, dest);

		if (bfsDir == null) return false;

		Direction[] dirs = new Direction[] { bfsDir, bfsDir.rotateLeft(), bfsDir.rotateRight() };
		Direction bestDir = null;
		for (int i = 0; i < dirs.length; i++) {
			if (bestDir != null) {
				// Only consider suboptimal directions if they have roads
				if (rc.senseTerrainTile(here.add(dirs[i])) != TerrainTile.ROAD) continue;
			}
			Direction dir = dirs[i];
			if (canMoveSafely(dir)) {
				if (moveIsAllowedByEngagementRules(dir)) {
					if (rc.senseTerrainTile(here.add(dir)) == TerrainTile.ROAD) { // then this direction has a road; go this way
						move(dir);
						return true;
					}
					bestDir = dir;
				}
			}
		}

		if (bestDir != null) {
			move(bestDir);
			return true;

		}
		return false;
	}

	public static void init(RobotController theRC) {
		rc = theRC;
	}

	public enum Sneak {
		YES,
		NO
	}

	public enum Engage {
		YES,
		NO
	}

	public static void goTo(MapLocation theDest, Sneak theSneak, Engage theEngage, int[] theNumEnemiesAttackingMoveDirs) throws GameActionException {
		// Debug.indicate("nav", 2, "goTo " + theDest.toString());

		sneak = (theSneak == Sneak.YES);
		engage = (theEngage == Engage.YES);
		numEnemiesAttackingMoveDirs = theNumEnemiesAttackingMoveDirs;
		fightDecisionIsCached = false;

		// Debug.indicate("nav", 1, "");

		if (!theDest.equals(dest)) {
			dest = theDest;
			bugState = BugState.DIRECT;
			// Debug.indicateAppend("nav", 1, "new dest: resetting bug to direct; ");
		}

		MapLocation here = rc.getLocation();

		if (here.equals(theDest)) return;

		if (!rc.isActive()) return;

		if (tryMoveBfs(here)) {
			bugState = BugState.DIRECT; // reset bug
			Debug.indicate("nav", 0, "using bfs");
			return;
		}

		Debug.indicate("nav", 0, "using bug");
		bugTo(dest);
	}

	private static void move(Direction dir) throws GameActionException {
		if (sneak) {
			Debug.indicate("sneak", 1, "Nav.move sneaking");
			rc.sneak(dir);
		} else {
			Debug.indicate("sneak", 1, "Nav.move moving regularly");
			rc.move(dir);
		}
	}

	private static boolean canMoveSafely(Direction dir) {
		return rc.canMove(dir) && !Bot.isInTheirHQAttackRange(rc.getLocation().add(dir));
	}

	private static boolean moveIsAllowedByEngagementRules(Direction dir) throws GameActionException {
		if (numEnemiesAttackingMoveDirs[dir.ordinal()] == 0) return true;
		if (!engage) return false;

		if (fightDecisionIsCached) return fightIsWinningDecision;

		Robot[] allEngagedEnemies = rc.senseNearbyGameObjects(Robot.class, rc.getLocation().add(dir), RobotType.SOLDIER.attackRadiusMaxSquared, rc.getTeam()
				.opponent());
		RobotInfo anEngagedEnemy = Util.findANonConstructingSoldier(allEngagedEnemies, rc);
		if (anEngagedEnemy == null) return true;

		int numNearbyAllies = 1 + Util.countNonConstructingSoldiers(rc.senseNearbyGameObjects(Robot.class, anEngagedEnemy.location, 29, rc.getTeam()), rc);
		int numNearbyEnemies = Util.countNonConstructingSoldiers(rc.senseNearbyGameObjects(Robot.class, anEngagedEnemy.location, 49, rc.getTeam().opponent()),
				rc);
		boolean ret = numNearbyAllies > numNearbyEnemies;
		fightIsWinningDecision = ret;
		fightDecisionIsCached = true;
		return ret;
	}
}