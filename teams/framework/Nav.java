package framework;

import battlecode.common.*;

public class Nav {
	private static MapLocation dest;
	private static RobotController rc;
	private static boolean sneak = false;

	private static MapLocation enemyHQ; //we can't ever go too near the enemy HQ

	private static boolean isNearTheirHQ(MapLocation loc) {
		return enemyHQ.distanceSquaredTo(loc) <= 25;
	}
	
	private static boolean canMoveSafely(Direction dir) {
		return rc.canMove(dir) && !isNearTheirHQ(rc.getLocation().add(dir));
	}
	
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

	private static boolean tryMoveDirect() throws GameActionException {
		MapLocation here = rc.getLocation();
		Direction toDest = here.directionTo(dest);
		Direction[] tryDirs = new Direction[] { toDest, toDest.rotateLeft(), toDest.rotateRight() };
		for (Direction tryDir : tryDirs) {
			if (canMoveSafely(tryDir)) {
				move(tryDir);
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
			if (canMoveSafely(dir)) return dir;
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

	// Set up the queue
	static MapLocation[] bfsQueue = new MapLocation[GameConstants.MAP_MAX_WIDTH * GameConstants.MAP_MAX_HEIGHT];
	static int bfsQueueHead = 0;
	static int bfsQueueTail = 0;
	static Direction[][] bfsPlan = new Direction[GameConstants.MAP_MAX_WIDTH][GameConstants.MAP_MAX_HEIGHT];
	static boolean[][] bfsWasQueued = new boolean[GameConstants.MAP_MAX_WIDTH][GameConstants.MAP_MAX_HEIGHT];

	// Reset the BFS plan and initialize the BFS algorithm
	static void bfsInit() {
		bfsQueueHead = 0;
		bfsQueueTail = 0;

		bfsPlan = new Direction[GameConstants.MAP_MAX_WIDTH][GameConstants.MAP_MAX_HEIGHT];
		bfsWasQueued = new boolean[GameConstants.MAP_MAX_WIDTH][GameConstants.MAP_MAX_HEIGHT];

		// Push dest onto queue
		bfsQueue[bfsQueueTail] = dest;
		bfsQueueTail++;
		bfsWasQueued[dest.x][dest.y] = true;
	}

	static void bfsBuildPlan() throws GameActionException {
		int mapWidth = rc.getMapWidth();
		int mapHeight = rc.getMapHeight();
		Direction[] dirs = new Direction[] { Direction.NORTH_WEST, Direction.SOUTH_WEST, Direction.SOUTH_EAST, Direction.NORTH_EAST, Direction.NORTH,
				Direction.WEST, Direction.SOUTH, Direction.EAST };
		int[] dirsX = new int[] { 1, 1, -1, -1, 0, 1, 0, -1 };
		int[] dirsY = new int[] { 1, -1, -1, 1, 1, 0, -1, 0 };

		while (bfsQueueHead != bfsQueueTail && Clock.getBytecodeNum() < 8500) {
			// pop a location from the queue
			MapLocation loc = bfsQueue[bfsQueueHead];
			bfsQueueHead++;

			int locX = loc.x;
			int locY = loc.y;
			for (int i = 8; i-- > 0;) {
				int x = locX + dirsX[i];
				int y = locY + dirsY[i];
				if (x > 0 && y > 0 && x < mapWidth && y < mapHeight && !bfsWasQueued[x][y]) {
					MapLocation newLoc = new MapLocation(x, y);
					if (rc.senseTerrainTile(newLoc) != TerrainTile.VOID && !isNearTheirHQ(newLoc)) {
						bfsPlan[x][y] = dirs[i];
						// push newLoc onto queue
						bfsQueue[bfsQueueTail] = newLoc;
						bfsQueueTail++;
						bfsWasQueued[x][y] = true;
					}
				}
			}
		}
	}

	public static void init(RobotController theRC) {
		rc = theRC;
		enemyHQ = rc.senseEnemyHQLocation();
	}

	public enum Sneak {
		YES, NO
	}
	
	public static void goTo(MapLocation theDest, Sneak theSneak) throws GameActionException {
		sneak = (theSneak == Sneak.YES);

		if (!theDest.equals(dest)) {
			dest = theDest;
			bugState = BugState.DIRECT;
			bfsInit(); // reset the BFS plan
		}

		MapLocation here = rc.getLocation();

		if (here.equals(theDest)) return;

		//if (bfsPlan[here.x][here.y] == null) {
		//	bfsBuildPlan();
		//}		

		if (!rc.isActive()) return;

//		Direction dir = bfsPlan[here.x][here.y];
		Direction dir = Bfs.readResult(here, dest, rc);
		// TODO: if we can't go in the desired direction, we need a better fallback than switching
		// to bug. For example, go in the closest direction, or if there is no close direction then
		// wait for 2-4 turns, or if that doesn't work, then bug
		if (dir != null && canMoveSafely(dir)) {
			Debug.indicate("nav", 0, "using bfs");
			move(dir);
		} else {
			Debug.indicate("nav", 0, "using bug");
			bugTo(dest);
		}
	}

	private static void move(Direction dir) throws GameActionException {
		if (sneak) rc.sneak(dir);
		else rc.move(dir);
	}

}
