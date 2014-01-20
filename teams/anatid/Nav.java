package anatid;

import battlecode.common.*;

public class Nav {
	private static MapLocation dest;
	private static RobotController rc;
	private static boolean sneak = false;
	private static boolean engage = false;
	private static int[] numEnemiesAttackingMoveDirs;
	private static boolean haveCachedEntersLosingFightDecision = false;
	private static boolean cachedEntersLosingFightDecision;

	private static MapLocation enemyHQ; // we can't ever go too near the enemy HQ

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

	private static boolean tryMoveDirect() throws GameActionException {
		MapLocation here = rc.getLocation();
		Direction toDest = here.directionTo(dest);
		int bestScore = 0;
		Direction bestDir = null;
		Direction[] dirs = new Direction[] { toDest, toDest.rotateLeft(), toDest.rotateRight() };
		for (Direction dir : dirs) {
			if (canMoveSafely(dir)) {
				if (moveIsAllowedByEngagementRules(dir)) {
					boolean road = rc.senseTerrainTile(here.add(dir)) == TerrainTile.ROAD;
					int score = road ? 2 : 1;
					if (score > bestScore) {
						bestScore = score;
						bestDir = dir;
					}
				}
			}
		}
		if (bestDir != null) {
			move(bestDir);
			return true;
		}
		return false;
	}

	private static void startBug() {
		bugStartDistSq = rc.getLocation().distanceSquaredTo(dest);
		bugLastMoveDir = rc.getLocation().directionTo(dest);
		bugLookStartDir = rc.getLocation().directionTo(dest);
		bugRotationCount = 0;
	}

	private static Direction findBugMoveDir() throws GameActionException {
		Direction dir = bugLookStartDir;
		for (int i = 8; i-- > 0;) {
			if (canMoveSafely(dir) && moveIsAllowedByEngagementRules(dir)) return dir;
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
		move(dir);
		bugRotationCount += calculateBugRotation(dir);
		bugLastMoveDir = dir;
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

	private static void reverseBugWallFollowDir() {
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
		return (bugRotationCount <= 0 || bugRotationCount >= 8) && rc.getLocation().distanceSquaredTo(dest) <= bugStartDistSq;
	}

	private static void bugTo(MapLocation theDest) throws GameActionException {
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

	private static boolean tryMoveBfs(MapLocation here) throws GameActionException {
		Direction bfsDir = Bfs.readResult(here, dest, rc);

		if (bfsDir == null) return false;

		Direction[] dirs = new Direction[] { bfsDir, bfsDir.rotateLeft(), bfsDir.rotateRight() };
		int bestScore = 0;
		Direction bestDir = null;
		for (int i = 0; i < dirs.length; i++) {
			Direction dir = dirs[i];
			if (canMoveSafely(dir)) {
				if (moveIsAllowedByEngagementRules(dir)) {
					int score = (i == 0 ? 2 : 1);

					// Give a big score for ending on a road, but only if the BFS doesn't tell us to backtrack
					// after deviating to get on a road:
					MapLocation next = here.add(dir);
					if (rc.senseTerrainTile(next) == TerrainTile.ROAD) {
						Direction nextBfsDir = Bfs.readResult(next, dest, rc);
						if (nextBfsDir != null) {
							MapLocation nextNext = next.add(nextBfsDir);
							if (!nextNext.isAdjacentTo(here)) {
								score += 10;
							}
						}
					}

					if (score > bestScore) {
						bestScore = score;
						bestDir = dir;
					}
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
		enemyHQ = rc.senseEnemyHQLocation();
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
		Debug.indicate("nav", 1, "goTo " + theDest.toString());

		sneak = (theSneak == Sneak.YES);
		engage = (theEngage == Engage.YES);
		numEnemiesAttackingMoveDirs = theNumEnemiesAttackingMoveDirs;

		if (!theDest.equals(dest)) {
			dest = theDest;
			bugState = BugState.DIRECT;
		}

		MapLocation here = rc.getLocation();

		if (here.equals(theDest)) return;

		if (!rc.isActive()) return;

		if (tryMoveBfs(here)) {
			Debug.indicate("nav", 0, "using bfs");
			return;
		}

		Debug.indicate("nav", 0, "using bug");
		bugTo(dest);
	}

	private static void move(Direction dir) throws GameActionException {
		if (sneak) rc.sneak(dir);
		else rc.move(dir);
	}

	private static boolean canMoveSafely(Direction dir) {
		return rc.canMove(dir) && !Util.inHQAttackRange(rc.getLocation().add(dir), enemyHQ);
	}

	private static boolean moveIsAllowedByEngagementRules(Direction dir) throws GameActionException {
		if (engage) return !moveEntersLosingFight(dir);
		else return !moveEntersFight(dir);
	}

	private static boolean moveEntersFight(Direction dir) throws GameActionException {
		return numEnemiesAttackingMoveDirs[dir.ordinal()] > 0;
	}

	private static boolean moveEntersLosingFight(Direction dir) throws GameActionException {
		if (!moveEntersFight(dir)) return false;
		// This function is expensive, so the first time we call it for any direction, we cache the result for the rest of
		// the turn so that we don't have to redo the analysis
		if (haveCachedEntersLosingFightDecision) return cachedEntersLosingFightDecision;

		MapLocation moveDest = rc.getLocation().add(dir);
		Robot[] engagedUnits = rc.senseNearbyGameObjects(Robot.class, moveDest, RobotType.SOLDIER.attackRadiusMaxSquared, rc.getTeam().opponent());
		MapLocation countingCenter = Util.closestNonConstructingSoldier(engagedUnits, moveDest, rc);
		if (countingCenter == null) return false;
		int numEnemies = Util.countNonConstructingSoldiers(rc.senseNearbyGameObjects(Robot.class, countingCenter, 30, rc.getTeam().opponent()), rc);
		int numAllies = 1 + Util.countNonConstructingSoldiers(rc.senseNearbyGameObjects(Robot.class, countingCenter, 30, rc.getTeam()), rc);
		boolean ret = numEnemies >= numAllies;
		Debug.indicate("losing", 1, "countingCenter = " + countingCenter.toString() + "; numEnemies = " + numEnemies + ", numAllies = " + numAllies);
		haveCachedEntersLosingFightDecision = true;
		cachedEntersLosingFightDecision = ret;
		return ret;
	}
}