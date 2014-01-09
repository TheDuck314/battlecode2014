package framework;

import battlecode.common.*;

public class BotNoiseTower extends Bot {
	public BotNoiseTower(RobotController theRC) {
		super(theRC);
	}

	MapLocation pastr = null;

	public void turn() throws GameActionException {
		if (!rc.isActive()) return;

		MapLocation nearestPastr = findNearestAlliedPastr();
		if (nearestPastr != pastr) {
			pastr = nearestPastr;
			if (pastr != null) {
				int startRound = Clock.getRoundNum();
				computePastrHerdPattern();
				int endRound = Clock.getRoundNum();
				System.out.println("herd pattern took " + (endRound - startRound) + " rounds.");
				return; // since this will take several turns
			}
		}

		herdTowardPastr();
	}

	private MapLocation findNearestAlliedPastr() {
		MapLocation[] pastrs = rc.sensePastrLocations(us);
		return Util.closest(pastrs, here);
	}

	int herdTargetIndex = 0;
	MapLocation[] herdPoints;
	Direction[][] squareHerdDirs;
	int numHerdPoints;

	void herdTowardPastr() throws GameActionException {
		MapLocation attackTarget;
		do {
			if (herdTargetIndex <= 0) {
				herdTargetIndex = numHerdPoints - 1;
			}

			MapLocation herdTarget = herdPoints[herdTargetIndex];
			Direction pointHerdDir = squareHerdDirs[herdTarget.x][herdTarget.y];
			attackTarget = herdTarget.add(Util.opposite(pointHerdDir), pointHerdDir.isDiagonal() ? 1 : 2);

			herdTargetIndex -= 1;
		} while (!rc.canAttackSquare(attackTarget));

		rc.attackSquareLight(attackTarget);

	}

	private void debugBytecodes(String message) {
		System.out.format("turn: %d, bytecodes: %d: %s\n", Clock.getRoundNum(), Clock.getBytecodeNum(), message);
	}

	// TODO: optimize lots
	private void computePastrHerdPattern() {
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
		Direction[][] herdDir;
		boolean[][] wasQueued = new boolean[GameConstants.MAP_MAX_WIDTH][GameConstants.MAP_MAX_HEIGHT];
		herdDir = new Direction[GameConstants.MAP_MAX_WIDTH][GameConstants.MAP_MAX_HEIGHT];

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
							herdDir[x][y] = dirs[i];
							//push newLoc onto queue
							locQueue[locQueueTail] = newLoc;
							locQueueTail++;
							wasQueued[x][y] = true;
						}
					}
				}
			}
		}
		
		herdPoints = locQueue;
		squareHerdDirs = herdDir;		
		numHerdPoints = locQueueTail;
	}

}
