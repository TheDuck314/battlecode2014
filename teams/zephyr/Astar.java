package zephyr;

import battlecode.common.*;

public class Astar {
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
	private static void writePageMetadata(int page, int roundLastUpdated, MapLocation dest, int priority, boolean finished) throws GameActionException {
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

	private static int findFinishedPage(MapLocation dest) throws GameActionException {
		for (int page = 0; page < NUM_PAGES; page++) {
			int metadata = readPageMetadata(page);
			if (getMetadataIsFinished(metadata) && getMetadataDestination(metadata).equals(dest)) {
				return page;
			}
		}

		return -1;
	}

	// root node is heap[1], children of node x are nodes 2x and 2x+1, parent of x is x/2, heap[0] is unused
	private static MapLocation source;
	private static MapLocation[] heap = new MapLocation[GameConstants.MAP_MAX_WIDTH * GameConstants.MAP_MAX_HEIGHT + 1];
	private static int[][] heapNode;
	private static double[][] bestDist;
	private static double[][] bestPriority;
	private static boolean[][] popped;
	private static Direction[][] bestDir;
	private static int heapSize;
	private static int numPopped = 0;

	private static void initAstar(MapLocation dest) {
		// System.out.println("initing dijkstra");
		heapSize = 0;
		heapNode = new int[GameConstants.MAP_MAX_WIDTH][GameConstants.MAP_MAX_HEIGHT];
		bestDist = new double[GameConstants.MAP_MAX_WIDTH][GameConstants.MAP_MAX_HEIGHT];
		bestPriority = new double[GameConstants.MAP_MAX_WIDTH][GameConstants.MAP_MAX_HEIGHT];
		popped = new boolean[GameConstants.MAP_MAX_WIDTH][GameConstants.MAP_MAX_HEIGHT];
		bestDir = new Direction[GameConstants.MAP_MAX_WIDTH][GameConstants.MAP_MAX_HEIGHT];

		// push the destination onto the priority queue
		bestDist[dest.x][dest.y] = 0;
		// System.out.println("pushing dest");
		priorityQueuePush(dest);
	}

	private static MapLocation priorityQueuePop() {
		MapLocation ret = heap[1];
		// System.out.println("popping root loc " + ret.toString());

		MapLocation bubbleDownLoc = heap[heapSize];
		double bubbleDownPriority = bestPriority[bubbleDownLoc.x][bubbleDownLoc.y];
		bubbleDown(1, bubbleDownLoc, bubbleDownPriority);
		heapSize--;
		numPopped++;
		return ret;
	}

	private static void bubbleDown(int node, MapLocation nodeLoc, double nodeBestPriority) {
		// System.out.println("bubbling down from node " + node + " = " + heap[node].toString());

		// bubble down
		int leftChildNode = 2 * node;
		if (leftChildNode > heapSize) {
			// no children exist
			// System.out.println("finished: no left child");
			setHeapNode(node, nodeLoc);
			return;
		}

		int rightChildNode = 2 * node + 1;
		if (rightChildNode > heapSize) {
			// only left child exists
			MapLocation leftChildLoc = heap[leftChildNode];
			double leftChildPriority = bestPriority[leftChildLoc.x][leftChildLoc.y];
			if (nodeBestPriority <= leftChildPriority) {
				// System.out.println("finished after NOT moving up left only child node " + leftChildNode + " = " + leftChildLoc.toString());
				setHeapNode(node, nodeLoc);
			} else {
				// System.out.println("finished after swapping with left only child node " + leftChildNode + " = " + leftChildLoc.toString());
				setHeapNode(node, leftChildLoc);
				setHeapNode(leftChildNode, nodeLoc);
			}
			return;
		}

		// both children exist
		MapLocation leftChildLoc = heap[leftChildNode];
		double leftChildPriority = bestPriority[leftChildLoc.x][leftChildLoc.y];
		MapLocation rightChildLoc = heap[rightChildNode];
		double rightChildPriority = bestPriority[rightChildLoc.x][rightChildLoc.y];

		int bestChildNode;
		MapLocation bestChildLoc;
		double bestChildPriority;
		if (leftChildPriority < rightChildPriority) {
			bestChildNode = leftChildNode;
			bestChildLoc = leftChildLoc;
			bestChildPriority = leftChildPriority;
		} else {
			bestChildNode = rightChildNode;
			bestChildLoc = rightChildLoc;
			bestChildPriority = rightChildPriority;
		}

		if (nodeBestPriority < bestChildPriority) {
			// System.out.println("finished after NOT moving up best child node " + bestChildNode + " = " + bestChildLoc.toString());
			setHeapNode(node, nodeLoc);
		} else {
			// System.out.println("swapping with best child node " + bestChildNode + " = " + bestChildLoc.toString());
			setHeapNode(node, bestChildLoc);
			bubbleDown(bestChildNode, nodeLoc, nodeBestPriority);
		}
	}

	private static void priorityQueuePush(MapLocation loc) {
		// System.out.println("pushing location " + loc.toString());
		double priority = bestDist[loc.x][loc.y] + Math.sqrt(loc.distanceSquaredTo(source));
		bestPriority[loc.x][loc.y] = priority;
		bubbleUp(heapSize + 1, loc, priority);
		heapSize++;
	}

	private static void bubbleUp(int node, MapLocation nodeLoc, double nodePriority) {
		// System.out.println("bubbling up location " + nodeLoc.toString() + " w/ dist " + nodeBestDist + " from node " + node);
		if (node == 1) {
			// System.out.println("ending bubble-up at root");
			setHeapNode(1, nodeLoc);
			return;
		}

		int parentNode = node / 2;
		MapLocation parentLoc = heap[parentNode];
		double parentBestPriority = bestPriority[parentLoc.x][parentLoc.y];

		if (nodePriority < parentBestPriority) {
			// System.out.println("swapping with parent node " + parentNode);
			setHeapNode(node, parentLoc);
			bubbleUp(parentNode, nodeLoc, nodePriority);
		} else {
			// System.out.println("settling at node " + node);
			setHeapNode(node, nodeLoc);
		}
	}

	private static void setHeapNode(int node, MapLocation loc) {
		heap[node] = loc;
		heapNode[loc.x][loc.y] = node;
	}

	private static Direction[] dirs = new Direction[] { Direction.NORTH_WEST, Direction.SOUTH_WEST, Direction.SOUTH_EAST, Direction.NORTH_EAST,
			Direction.NORTH, Direction.WEST, Direction.SOUTH, Direction.EAST };
	private static int[] dirsX = new int[] { 1, 1, -1, -1, 0, 1, 0, -1 };
	private static int[] dirsY = new int[] { 1, -1, -1, 1, 1, 0, -1, 0 };
	private static double[] dirCosts = new double[] { 2.8, 2.8, 2.8, 2.8, 2, 2, 2, 2 };
	private static double[] dirRoadCosts = new double[] { 1.4, 1.4, 1.4, 1.4, 1, 1, 1, 1 };

	private static MapLocation previousDest = null;
	private static int previousRoundWorked = -1;
	private static int previousPage = -1;

	private static boolean doneFlag = false;

	// HQ or pastr calls this function to spend spare bytecodes computing paths for soldiers
	public static void work(MapLocation dest, int priority, int bytecodeLimit) throws GameActionException {
		if (doneFlag) return;

		//int page = findFreePage(dest, priority);
		int page = findFinishedPage(dest);
		if (page == -1) return; // We can't do any work, or don't have to

		source = Bot.ourHQ;
		
		if (!dest.equals(previousDest)) {
			initAstar(dest);
		}

		previousDest = dest;
		previousRoundWorked = Clock.getRoundNum();
		previousPage = page;

		boolean destInSpawn = dest.distanceSquaredTo(Bot.theirHQ) <= 25;

		while (heapSize != 0 && Clock.getBytecodeNum() < bytecodeLimit) {
			// pop a location from the queue. We now know the optimal path from this location
			MapLocation loc = priorityQueuePop();
			popped[loc.x][loc.y] = true;
			if (!loc.equals(dest)) publishResult(page, loc, dest, bestDir[loc.x][loc.y]);
			if (loc.equals(source)) {
				doneFlag = true;
				break;
			}

			int locX = loc.x;
			int locY = loc.y;
			double locBestDist = bestDist[locX][locY];
			for (int i = 8; i-- > 0;) {
				int x = locX + dirsX[i];
				int y = locY + dirsY[i];
				MapLocation newLoc = new MapLocation(x, y);
				TerrainTile newLocTerrain = rc.senseTerrainTile(newLoc);
				if (newLocTerrain == TerrainTile.OFF_MAP || newLocTerrain == TerrainTile.VOID) continue;
				if (popped[x][y]) continue;
				if (!destInSpawn && Bot.isInTheirHQAttackRange(newLoc)) continue;

				double newLocDist = locBestDist + (newLocTerrain == TerrainTile.ROAD ? dirRoadCosts[i] : dirCosts[i]);
				if (bestDist[x][y] == 0) {
					// node hasn't been queued yet
					bestDist[x][y] = newLocDist;
					bestDir[x][y] = dirs[i];
					priorityQueuePush(newLoc);
				} else if (newLocDist < bestDist[x][y]) {
					// node has been queued but we have a better distance
					bestDist[x][y] = newLocDist;
					bestDir[x][y] = dirs[i];
					double newLocPriority = bestDist[newLoc.x][newLoc.y] + Math.sqrt(newLoc.distanceSquaredTo(source));
					bestPriority[newLoc.x][newLoc.y] = newLocPriority;
					bubbleUp(heapNode[x][y], newLoc, newLocPriority);
				}
			}
		}

		// System.out.println("ending Dijsktra work turn with heapSize = " + heapSize);

		boolean finished = heapSize == 0;
		// writePageMetadata(page, Clock.getRoundNum(), dest, priority, finished);
		Debug.indicate("path", 0, "Astar num popped = " + numPopped);
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
