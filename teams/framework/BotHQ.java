package framework;

import battlecode.common.*;

public class BotHQ extends Bot {
	public BotHQ(RobotController theRC) {
		super(theRC);

		cowGrowth = rc.senseCowGrowth();
	}

	// Strategic info
	int virtualSpawnCountdown = 0;
	int maxEnemySpawns;
	int numEnemyPastrs;
	int maxEnemySoldiers; // An upper bound on the # of enemy soldiers
	int numAlliedPastrs;
	int numAlliedSoldiers;
	int numAlliedNoiseTowers;
	MapLocation[] theirPastrs;
	MapLocation[] ourPastrs;
	double ourMilk;
	double theirMilk;

	boolean attackModeTriggered = false;

	// Guess how many bots the opponent has
	// TODO: account for pop cap
	// TODO: record kills of enemy units
	private void updateStrategicInfo() throws GameActionException {
		theirPastrs = rc.sensePastrLocations(them);
		numEnemyPastrs = theirPastrs.length;
		ourPastrs = rc.sensePastrLocations(us);
		numAlliedPastrs = ourPastrs.length;

		virtualSpawnCountdown--;
		if (virtualSpawnCountdown <= 0) {
			maxEnemySpawns++;
			int maxEnemyPopCount = maxEnemySpawns + numEnemyPastrs;
			virtualSpawnCountdown = (int) Math.round(GameConstants.HQ_SPAWN_DELAY_CONSTANT_1
					+ Math.pow(maxEnemyPopCount - 1, GameConstants.HQ_SPAWN_DELAY_CONSTANT_2));
		}
		maxEnemySoldiers = maxEnemySpawns - numEnemyPastrs;

		numAlliedSoldiers = 0;
		Robot[] alliedUnits = rc.senseNearbyGameObjects(Robot.class, 999999, us);
		for (int i = alliedUnits.length; i-- > 0;) {
			Robot unit = alliedUnits[i];
			RobotInfo info = rc.senseRobotInfo(unit);
			if (info.type == RobotType.SOLDIER) numAlliedSoldiers++;
		}

		ourMilk = rc.senseTeamMilkQuantity(us);
		theirMilk = rc.senseTeamMilkQuantity(them);
	}

	public void turn() throws GameActionException {
		updateStrategicInfo();

		if (Clock.getRoundNum() == 0) {
			MessageBoard.BEST_PASTR_LOC.writeMapLocation(null, rc);
		}

		if (rc.isActive()) {
			if (attackEnemies()) return;
			spawnSoldier();
		}
	}

	private double[][] cowGrowth;
	private double[][] computedPastrScores = null;
	private MapLocation computedBestPastrLocation = null;

	private void computePastrScores() {
		int mapWidth = rc.getMapWidth();
		int mapHeight = rc.getMapHeight();

		int[] yOffsets = new int[] { 2, 2, 2, 1, 1, 1, 1, 1, 0, 0, 0, 0, 0, -1, -1, -1, -1, -1, -2, -2, -2 };
		int[] xOffsets = new int[] { -1, 0, 1, -2, -1, 0, 1, 2, -2, -1, 0, 1, 2, -2, -1, 0, 1, 2, -1, 0, 1 };

		double[][] pastrScores = new double[mapWidth][mapHeight];
		for (int x = mapWidth; x-- > 0;) {
			for (int y = mapHeight; y-- > 0;) {
				if (cowGrowth[x][y] > 0) {
					if (rc.senseTerrainTile(new MapLocation(x, y)) != TerrainTile.VOID) {
						for (int i = xOffsets.length; i-- > 0;) {
							int pX = x + xOffsets[i];
							int pY = y + yOffsets[i];
							if (pX > 0 && pY > 0 && pX < mapWidth && pY < mapHeight) {
								pastrScores[pX][pY] += cowGrowth[x][y];
							}
						}
					} else {
						pastrScores[x][y] -= 999999; // make it so we don't make pastrs on void squares
					}
				}
				pastrScores[x][y] -= Math.sqrt(ourHQ.distanceSquaredTo(new MapLocation(x, y)));
			}
		}

		for (int y = 0; y < mapHeight; y++) {
			for (int x = 0; x < mapWidth; x++) {
				if (pastrScores[x][y] < -999) System.out.format("XX ");
				else System.out.format("%03d ", (int) pastrScores[x][y]);
			}
			System.out.println();
		}

		computedPastrScores = pastrScores;
	}

	private void computeBestPastrLocation() {
		MapLocation bestPastrLocation = null;
		double bestPastrScore = -999;

		int mapWidth = rc.getMapWidth();
		int mapHeight = rc.getMapHeight();
		for (int x = mapWidth; x-- > 0;) {
			for (int y = mapHeight; y-- > 0;) {
				if (computedPastrScores[x][y] > bestPastrScore) {
					bestPastrScore = computedPastrScores[x][y];
					bestPastrLocation = new MapLocation(x, y);
				}
			}
		}

		computedBestPastrLocation = bestPastrLocation;
		System.out.println("bestPastrLocation = " + bestPastrLocation.toString());
	}

	private void adjustPastrScores(MapLocation existingPastr) {
		int mapWidth = rc.getMapWidth();
		int mapHeight = rc.getMapHeight();
		int[] yOffsets = new int[] { 2, 2, 2, 1, 1, 1, 1, 1, 0, 0, 0, 0, 0, -1, -1, -1, -1, -1, -2, -2, -2 };
		int[] xOffsets = new int[] { -1, 0, 1, -2, -1, 0, 1, 2, -2, -1, 0, 1, 2, -2, -1, 0, 1, 2, -1, 0, 1 };

		for (int i = xOffsets.length; i-- > 0;) {
			int x1 = existingPastr.x + xOffsets[i];
			int y1 = existingPastr.y + yOffsets[i];
			if (x1 > 0 && y1 > 0 && x1 < mapWidth && y1 < mapHeight) {
				for (int j = xOffsets.length; j-- > 0;) {
					int x2 = x1 + xOffsets[j];
					int y2 = y1 + yOffsets[j];
					if (x2 > 0 && y2 > 0 && x2 < mapWidth && y2 < mapHeight) {
						computedPastrScores[x2][y2] -= cowGrowth[x1][y1];
					}
				}
			}
		}
	}

	private boolean attackEnemies() throws GameActionException {
		Robot[] enemies = rc.senseNearbyGameObjects(Robot.class, RobotType.HQ.attackRadiusMaxSquared, them);
		if (enemies.length == 0) return false;

		RobotInfo info = rc.senseRobotInfo(enemies[0]);
		MapLocation target = info.location;
		rc.attackSquare(target);
		return true;
	}

	private boolean spawnSoldier() throws GameActionException {
		if (rc.senseRobotCount() >= GameConstants.MAX_ROBOTS) return false;
		// if (rc.senseRobotCount() >= 2) return false;

		Direction dir = Util.opposite(ourHQ.directionTo(theirHQ)).rotateLeft();
		for (int i = 8; i-- > 0;) {
			if (rc.canMove(dir)) {
				rc.spawn(dir);
				return true;
			} else {
				dir = dir.rotateRight();
			}
		}

		return false;
	}
}
