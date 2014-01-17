package anatid16_noisepastr;

import battlecode.common.*;

public class HerdPattern {
	private static final int baseChannel = 4 * GameConstants.MAP_MAX_WIDTH * GameConstants.MAP_MAX_HEIGHT;

	private static void writeNumHerdPoints(int numHerdPoints, RobotController rc) throws GameActionException {
		rc.broadcast(baseChannel, numHerdPoints);
	}

	public static int readNumHerdPoints(RobotController rc) throws GameActionException {
		return rc.readBroadcast(baseChannel);
	}

	private static void writeHerdDir(int order, MapLocation loc, Direction dir, RobotController rc) throws GameActionException {
		int channel = baseChannel + 1 + order;
		int data = (dir.ordinal() * 100000) + (loc.x * 100) + (loc.y);
		rc.broadcast(channel, data);
	}

	public static MapLocation readHerdMapLocation(int index, RobotController rc) throws GameActionException {
		int channel = baseChannel + 1 + index;
		int data = rc.readBroadcast(channel);
		data %= 100000;
		return new MapLocation(data / 100, data % 100);
	}

	public static Direction readHerdDir(int index, RobotController rc) throws GameActionException {
		int channel = baseChannel + 1 + index;
		int data = rc.readBroadcast(channel);
		Direction dir = Direction.values()[data / 100000];
		return dir;
	}

	public static void computeAndPublish(MapLocation here, MapLocation pastr, RobotController rc) throws GameActionException {
		// Useful data
		int attackRange = RobotType.NOISETOWER.attackRadiusMaxSquared;
		int mapWidth = rc.getMapWidth();
		int mapHeight = rc.getMapHeight();
		Direction[] dirs = new Direction[] { Direction.NORTH_WEST, Direction.SOUTH_WEST, Direction.SOUTH_EAST, Direction.NORTH_EAST, Direction.NORTH,
				Direction.WEST, Direction.SOUTH, Direction.EAST };
		int[] dirsX = new int[] { 1, 1, -1, -1, 0, 1, 0, -1 };
		int[] dirsY = new int[] { 1, -1, -1, 1, 1, 0, -1, 0 };

		// Set up the queue
		MapLocation[] locQueue = new MapLocation[(2 * RobotType.NOISETOWER.attackRadiusMaxSquared + 1) * (2 * RobotType.NOISETOWER.attackRadiusMaxSquared + 1)];
		int locQueueHead = 0;
		int locQueueTail = 0;
		boolean[][] wasQueued = new boolean[GameConstants.MAP_MAX_WIDTH][GameConstants.MAP_MAX_HEIGHT];

		// Push pastr onto queue
		locQueue[locQueueTail] = pastr;
		locQueueTail++;
		wasQueued[pastr.x][pastr.y] = true;

		while (locQueueHead != locQueueTail) {
			// pop a location from the queue
			MapLocation loc = locQueue[locQueueHead];
			locQueueHead++;

			int locX = loc.x;
			int locY = loc.y;
			for (int i = 8; i-- > 0;) {
				int x = locX + dirsX[i];
				int y = locY + dirsY[i];
				if (x > 0 && y > 0 && x < mapWidth && y < mapHeight && !wasQueued[x][y]) {
					MapLocation newLoc = new MapLocation(x, y);
					if (here.distanceSquaredTo(newLoc) <= attackRange) {
						if (rc.senseTerrainTile(newLoc) != TerrainTile.VOID) {
							writeHerdDir(locQueueTail, newLoc, dirs[i], rc);

							// push newLoc onto queue
							locQueue[locQueueTail] = newLoc;
							locQueueTail++;
							wasQueued[x][y] = true;
						}
					}
				}
			}
		}

		writeNumHerdPoints(locQueueHead, rc);
	}
}
