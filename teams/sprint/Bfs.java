package sprint;

import battlecode.common.*;

public class Bfs {

	// Set up the queue
	static MapLocation[] locQueue = new MapLocation[GameConstants.MAP_MAX_WIDTH * GameConstants.MAP_MAX_HEIGHT];
	static int locQueueHead = 0;
	static int locQueueTail = 0;
	static boolean[][] wasQueued = new boolean[GameConstants.MAP_MAX_WIDTH][GameConstants.MAP_MAX_HEIGHT];

	static Direction[] dirs = new Direction[] { Direction.NORTH_WEST, Direction.SOUTH_WEST, Direction.SOUTH_EAST, Direction.NORTH_EAST, Direction.NORTH,
			Direction.WEST, Direction.SOUTH, Direction.EAST };
	static int[] dirsX = new int[] { 1, 1, -1, -1, 0, 1, 0, -1 };
	static int[] dirsY = new int[] { 1, -1, -1, 1, 1, 0, -1, 0 };

	static MapLocation previousDest = null;

	// initialize the BFS algorithm
	static void initQueue(MapLocation dest) {
		locQueueHead = 0;
		locQueueTail = 0;
		
		wasQueued = new boolean[GameConstants.MAP_MAX_WIDTH][GameConstants.MAP_MAX_HEIGHT];

		// Push dest onto queue
		locQueue[locQueueTail] = dest;
		locQueueTail++;
		wasQueued[dest.x][dest.y] = true;		
	}

	// HQ calls this function to spend spare bytecodes computing paths for soldiers
	public static void work(MapLocation dest, RobotController rc, int bytecodeLimit) throws GameActionException {
		if (previousDest == null || !previousDest.equals(dest)) {
			initQueue(dest);
		}
		previousDest = dest;
		
		int mapWidth = rc.getMapWidth();
		int mapHeight = rc.getMapHeight();
		MapLocation enemyHQ = rc.senseEnemyHQLocation();
		boolean destInSpawn = dest.distanceSquaredTo(enemyHQ) <= 25;

		while (locQueueHead != locQueueTail && Clock.getBytecodeNum() < bytecodeLimit) {
			// pop a location from the queue
			MapLocation loc = locQueue[locQueueHead];
			locQueueHead++;

			int locX = loc.x;
			int locY = loc.y;
			for (int i = 8; i-- > 0;) { //3 + 8*3 = 27
				int x = locX + dirsX[i]; //6
				int y = locY + dirsY[i]; //6
				if (x > 0 && y > 0 && x < mapWidth && y < mapHeight && !wasQueued[x][y]) { //max 16
					MapLocation newLoc = new MapLocation(x, y); //6
					if (rc.senseTerrainTile(newLoc) != TerrainTile.VOID /* 5 + 10 */ && (destInSpawn || !Util.inHQAttackRange(newLoc, enemyHQ)/*usually 22*/)) {
						publishResult(newLoc, dest, dirs[i], rc); // 7 + publishResult

						// push newLoc onto queue
						locQueue[locQueueTail] = newLoc; //4
						locQueueTail++; //4
						wasQueued[x][y] = true; //6
					}
				}
			}
		}
		
//		Debug.indicate("bfs", 0, "bfs has reached " + locQueueTail + " locations\n");
	}

	private static int locChannel(MapLocation loc) {
		return GameConstants.MAP_MAX_WIDTH * loc.x + loc.y;
	}

	private static void publishResult(MapLocation here, MapLocation dest, Direction dir, RobotController rc) throws GameActionException {
		int data = (dir.ordinal() * 100000) + (dest.x * 100) + (dest.y);
		int channel = locChannel(here);
		rc.broadcast(channel, data);
	}

	// Soldiers call this to get pathing directions
	// TODO: fix case of pathing to (0,0)
	public static Direction readResult(MapLocation here, MapLocation dest, RobotController rc) throws GameActionException {		
		int data = rc.readBroadcast(locChannel(here));
		if (((dest.x *100) + (dest.y)) != (data % 100000)) return null;
		return Direction.values()[data / 100000];
	}
}
