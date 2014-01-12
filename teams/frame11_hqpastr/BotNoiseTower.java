package frame11_hqpastr;

import battlecode.common.*;

public class BotNoiseTower extends Bot {
	public BotNoiseTower(RobotController theRC) {
		super(theRC);
		Debug.init(theRC, "herd");
	}

	MapLocation pastr = null;

	public void turn() throws GameActionException {
		if (!rc.isActive()) return;

		MapLocation nearestPastr = findNearestAlliedPastr();
		if (nearestPastr != pastr) {
			pastr = nearestPastr;
			// if (pastr != null) {
			// Util.timerStart();
			// computePastrHerdPattern();
			// Util.timerEnd("compute herd pattern");
			// return; // since this will take several turns
			// }
		}

		herdTowardPastrSmart();
		// herdTowardPastrDumb();
	}

	private MapLocation findNearestAlliedPastr() {
		MapLocation[] pastrs = rc.sensePastrLocations(us);
		return Util.closest(pastrs, here);
	}

	int radius = 20;
	Direction attackDir = Direction.NORTH;

	private void herdTowardPastrDumb() throws GameActionException {
		MapLocation target = null;
		do {
			int dx = radius * attackDir.dx;
			int dy = radius * attackDir.dy;
			if (attackDir.isDiagonal()) {
				dx = (int) (dx / 1.4);
				dy = (int) (dy / 1.4);
			}
			target = new MapLocation(pastr.x + dx, pastr.y + dy);
			attackDir = attackDir.rotateRight();
			if (attackDir == Direction.NORTH) radius--;
			if (radius <= 5) radius = 20;
		} while (tooFarOffMap(target) || !rc.canAttackSquare(target));

		rc.attackSquare(target);
	}

	boolean tooFarOffMap(MapLocation loc) {
		int W = 2;
		return loc.x < -W || loc.y < -W || loc.x >= rc.getMapWidth() + W || loc.y >= rc.getMapWidth() + W;
	}

	int herdTargetIndex = 0;
	MapLocation[] herdPoints;
	Direction[][] squareHerdDirs;
	int numHerdPoints;
	// static final int maxHerdPoints = 200;
	static final int maxHerdPoints = 1000;

	void herdTowardPastrSmart() throws GameActionException {
		MapLocation attackTarget;
		numHerdPoints = HerdPattern.Band.ONE.readNumHerdPoints(rc);
		do {
			if (herdTargetIndex <= 0) {
				herdTargetIndex = Math.min(400, numHerdPoints - 1);
			}

			// MapLocation herdTarget = herdPoints[herdTargetIndex];
			// Direction pointHerdDir = squareHerdDirs[herdTarget.x][herdTarget.y];
			MapLocation herdTarget = HerdPattern.Band.ONE.readHerdMapLocation(herdTargetIndex, rc);
			Direction pointHerdDir = HerdPattern.Band.ONE.readHerdDir(herdTargetIndex, rc);

			// attackTarget = herdTarget.add(Util.opposite(pointHerdDir), pointHerdDir.isDiagonal() ? 1 : 2);
			attackTarget = herdTarget.add(Util.opposite(pointHerdDir), pointHerdDir.isDiagonal() ? 2 : 3);

			int skip = herdTargetIndex < 100 ? FastRandom.randInt(1, 3) : FastRandom.randInt(1, 7);
			for (int i = skip; i-- > 0;) {
				herdTargetIndex--;
				MapLocation nextHerdTarget = HerdPattern.Band.ONE.readHerdMapLocation(herdTargetIndex, rc);
				if (nextHerdTarget.x == 0 || nextHerdTarget.y == 0 || nextHerdTarget.x == rc.getMapWidth() - 1 || nextHerdTarget.y == rc.getMapHeight() - 1) break;
			}

			// if(herdTargetIndex < 50) herdTargetIndex -= FastRandom.randInt(1, 3);
			// else herdTargetIndex -= FastRandom.randInt(1, 7);
			Debug.indicate("herd", 0, "" + herdTargetIndex);
			// herdTargetIndex -= 1 + (int)Math.sqrt(herdTargetIndex/2);
		} while (!rc.canAttackSquare(attackTarget));

		// rc.attackSquareLight(attackTarget);
		rc.attackSquare(attackTarget);
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

		while (locQueueHead != locQueueTail && locQueueTail < maxHerdPoints) {
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
							// push newLoc onto queue
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
