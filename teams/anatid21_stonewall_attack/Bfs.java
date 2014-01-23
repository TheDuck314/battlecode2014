package anatid21_stonewall_attack;

import battlecode.common.*;

public class Bfs {

	private static int NUM_PAGES;
	private static int PAGE_SIZE;
	private static int MAP_HEIGHT;
	private static final int MAX_PAGES = 5;

	private static RobotController rc;

	public static void init(RobotController theRC) {
		rc = theRC;
		MAP_HEIGHT = rc.getMapHeight();
		PAGE_SIZE = rc.getMapWidth() * MAP_HEIGHT;
		NUM_PAGES = Math.min(40000 / PAGE_SIZE, MAX_PAGES);
	}

	private static final int pageMetadataBaseChannel = GameConstants.BROADCAST_MAX_CHANNELS - 100;

	public static final int PRIORITY_HIGH = 2;
	public static final int PRIORITY_LOW = 1;

	// Page allocation:
	// From time to time various different robots will want to use the Bfs class to
	// calculate pathing information for various different destinations. In each case, we need
	// to be able to answer the following questions:
	// - Does a complete, undamaged pathfinding map already exist in some page for the specified destination?
	// If so, no point doing any more work on that destination.
	// - Is there another robot that is at this very moment computing pathing information for the specified destination?
	// If so, no point duplicating their work
	// - If no complete undamaged map exists and no other robot is working on the specified destination, is
	// there a free page that can be used to build a map for the specified destination? By "free" we mean a
	// page that (a) is not at this very moment being added to by another robot and (b) does not contain
	// pathing information for a destination more important than the specified one.
	// If such a free page exists, we can work on it.

	// metadata format:
	// fprrrrxxyy
	// f = finished or not
	// p = priority
	// rrrr = round last updated
	// xx = dest x coordinate
	// yy = dest y coordinate
	private static void writePageMetadata(int page, int roundLastUpdated, MapLocation dest, int priority, boolean finished)
			throws GameActionException {
		int channel = pageMetadataBaseChannel + page;
		int data = (finished ? 1000000000 : 0) + 100000000 * priority + 10000 * roundLastUpdated + MAP_HEIGHT * dest.x + dest.y;
		rc.broadcast(channel, data);
	}

	private static boolean getMetadataIsFinished(int metadata) {
		return metadata >= 1000000000;
	}

	private static int getMetadataPriority(int metadata) {
		return (metadata % 1000000000) / 100000000;
	}

	private static int getMetadataRoundLastUpdated(int metadata) {
		return (metadata % 100000000) / 10000;
	}

	private static MapLocation getMetadataDestination(int metadata) {
		metadata %= 10000;
		return new MapLocation(metadata / MAP_HEIGHT, metadata % MAP_HEIGHT);
	}

	private static int readPageMetadata(int page) throws GameActionException {
		int channel = pageMetadataBaseChannel + page;
		int data = rc.readBroadcast(channel);
		return data;
	}

	private static int findFreePage(MapLocation dest, int priority) throws GameActionException {
		// see if we can reuse a page we used before
		if (dest.equals(previousDest) && previousPage != -1) {
			int previousPageMetadata = readPageMetadata(previousPage);
			if (getMetadataRoundLastUpdated(previousPageMetadata) == previousRoundWorked && getMetadataDestination(previousPageMetadata).equals(dest)) {
				if (getMetadataIsFinished(previousPageMetadata)) {
					return -1; // we're done! don't do any work!
				} else {
					return previousPage;
				}
			}
		}

		// Check to see if anyone else is working on this destination. If so, don't bother doing anything.
		// But as we loop over pages, look for the page that hasn't been touched in the longest time
		int lastRound = Clock.getRoundNum() - 1;
		int oldestPage = -1;
		int oldestPageRoundUpdated = 999999;
		for (int page = 0; page < NUM_PAGES; page++) {
			int metadata = readPageMetadata(page);
			if (metadata == 0) { // untouched page
				if (oldestPageRoundUpdated > 0) {
					oldestPage = page;
					oldestPageRoundUpdated = 0;
				}
			} else {
				int roundUpdated = getMetadataRoundLastUpdated(metadata);
				boolean isFinished = getMetadataIsFinished(metadata);
				if (roundUpdated >= lastRound || isFinished) {
					if (getMetadataDestination(metadata).equals(dest)) {
						return -1; // someone else is on the case!
					}
				}
				if (roundUpdated < oldestPageRoundUpdated) {
					oldestPageRoundUpdated = roundUpdated;
					oldestPage = page;
				}
			}
		}

		// No one else is working on our dest. If we found an inactive page, use that one.
		if (oldestPage != -1 && oldestPageRoundUpdated < lastRound) return oldestPage;

		// If there aren't any inactive pages, and we have high priority, just trash page 0:
		if (priority == PRIORITY_HIGH) return 0;

		// otherwise, give up:
		return -1;
	}

	// Set up the queue
	private static MapLocation[] locQueue = new MapLocation[GameConstants.MAP_MAX_WIDTH * GameConstants.MAP_MAX_HEIGHT];
	private static int locQueueHead = 0;
	private static int locQueueTail = 0;
	private static boolean[][] wasQueued = new boolean[GameConstants.MAP_MAX_WIDTH][GameConstants.MAP_MAX_HEIGHT];

	private static Direction[] dirs = new Direction[] { Direction.NORTH_WEST, Direction.SOUTH_WEST, Direction.SOUTH_EAST, Direction.NORTH_EAST,
			Direction.NORTH, Direction.WEST, Direction.SOUTH, Direction.EAST };
	private static int[] dirsX = new int[] { 1, 1, -1, -1, 0, 1, 0, -1 };
	private static int[] dirsY = new int[] { 1, -1, -1, 1, 1, 0, -1, 0 };

	private static MapLocation previousDest = null;
	private static int previousRoundWorked = -1;
	private static int previousPage = -1;

	// initialize the BFS algorithm
	private static void initQueue(MapLocation dest) {
		locQueueHead = 0;
		locQueueTail = 0;

		wasQueued = new boolean[GameConstants.MAP_MAX_WIDTH][GameConstants.MAP_MAX_HEIGHT];

		// Push dest onto queue
		locQueue[locQueueTail] = dest;
		locQueueTail++;
		wasQueued[dest.x][dest.y] = true;
	}

	// HQ or pastr calls this function to spend spare bytecodes computing paths for soldiers
	public static void work(MapLocation dest, int priority, int bytecodeLimit) throws GameActionException {
		int page = findFreePage(dest, priority);
//		Debug.indicate("pages", 1, "Pathing to " + dest.toString() + "; using page " + page);
		if (page == -1) return; // We can't do any work, or don't have to

		if (!dest.equals(previousDest)) {
//			Debug.indicate("pages", 0, "initingQueue");
			initQueue(dest);
		} else {
//			Debug.indicate("pages", 0, "queue already inited");
		}

		previousDest = dest;
		previousRoundWorked = Clock.getRoundNum();
		previousPage = page;

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
			for (int i = 8; i-- > 0;) {
				int x = locX + dirsX[i]; 
				int y = locY + dirsY[i]; 
				if (x >= 0 && y >= 0 && x < mapWidth && y < mapHeight && !wasQueued[x][y]) {
					MapLocation newLoc = new MapLocation(x, y);
					if (rc.senseTerrainTile(newLoc) != TerrainTile.VOID && (destInSpawn || !Bot.isInTheirHQAttackRange(newLoc))) {
						publishResult(page, newLoc, dest, dirs[i]);

						// push newLoc onto queue
						locQueue[locQueueTail] = newLoc;
						locQueueTail++;
						wasQueued[x][y] = true;
					}
				}
			}
		}

		boolean finished = locQueueHead == locQueueTail;
//		Debug.indicate("pages", 2, "finished = " + finished + "; locQueueHead = " + locQueueHead);
		writePageMetadata(page, Clock.getRoundNum(), dest, priority, finished);
	}

	private static int locChannel(int page, MapLocation loc) {
		return PAGE_SIZE * page + MAP_HEIGHT * loc.x + loc.y;
	}

	// We store the data in this format:
	// 10d0xxyy
	// 1 = validation to prevent mistaking the initial 0 value for a valid pathing instruction
	// d = direction to move
	// xx = x coordinate of destination
	// yy = y coordinate of destination
	private static void publishResult(int page, MapLocation here, MapLocation dest, Direction dir) throws GameActionException {
		int data = 10000000 + (dir.ordinal() * 100000) + (dest.x * MAP_HEIGHT) + (dest.y);
		int channel = locChannel(page, here);
		rc.broadcast(channel, data);
	}

	// Soldiers call this to get pathing directions
	public static Direction readResult(MapLocation here, MapLocation dest) throws GameActionException {
		for (int page = 0; page < NUM_PAGES; page++) {
			int data = rc.readBroadcast(locChannel(page, here));
			if (data != 0) { // all valid published results are != 0
				data -= 10000000;
				if (((dest.x * MAP_HEIGHT) + (dest.y)) == (data % 100000)) {
					return Direction.values()[data / 100000];
				}
			}
		}
		return null;
	}
}
