package anatid20_oldmicro;

import battlecode.common.*;

public class HerdPattern {
	private static final int baseChannel = 40000;

	private static void writeHerdDir(MapLocation loc, Direction dir, RobotController rc) throws GameActionException {
		int channel = baseChannel + GameConstants.MAP_MAX_HEIGHT * loc.x + loc.y;
		int data = 10 + dir.ordinal();
		rc.broadcast(channel, data);
	}

	public static Direction readHerdDir(MapLocation loc, RobotController rc) throws GameActionException {
		int channel = baseChannel + GameConstants.MAP_MAX_HEIGHT * loc.x + loc.y;
		int data = rc.readBroadcast(channel);
		if (data == 0) return null;
		Direction dir = Direction.values()[data - 10];
		return dir;
	}

	public static void computeAndPublish(MapLocation here, MapLocation pastr, RobotController rc) throws GameActionException {
		// Useful data
		int attackRange = (int) (Math.sqrt(RobotType.NOISETOWER.attackRadiusMaxSquared) - 3); // -3 becauase we actually have to shoot past the square
		int attackRangeSq = attackRange * attackRange;
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
					if (here.distanceSquaredTo(newLoc) <= attackRangeSq) {
						if (rc.senseTerrainTile(newLoc) != TerrainTile.VOID) {
							writeHerdDir(newLoc, dirs[i], rc);

							// push newLoc onto queue
							locQueue[locQueueTail] = newLoc;
							locQueueTail++;
							wasQueued[x][y] = true;
						}
					}
				}
			}
		}
	}
}
