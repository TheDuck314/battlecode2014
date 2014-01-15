package anatid;

import battlecode.common.*;

public class BotHQ extends Bot {
	public BotHQ(RobotController theRC) {
		super(theRC);
//		Debug.init(rc, "map");

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
	double ourMilk;
	double theirMilk;

	boolean attackModeTriggered = false;
	MapLocation attackModeTarget = null;

	MapLocation[] theirPastrs;
	MapLocation[] ourPastrs;
	RobotInfo[] allAllies;

	// Used for pastr placement
	double[][] cowGrowth;
	double[][] computedPastrScores = null;
	MapLocation computedBestPastrLocation = null;

	public void turn() throws GameActionException {
		updateStrategicInfo();

		// First turn gets special treatment: spawn then do a bunch of computation
		// (which we devoutly hope will finished before the spawn timer is up
		// and before anyone attacks us).
		if (Clock.getRoundNum() == 0) {
			doFirstTurn();
			return;
		}

		if (rc.isActive()) {
			spawnSoldier();
		}

		attackEnemies();

		directStrategy();

		// Use spare bytecodes to do pathing computations
		MapLocation pathingDest;
		if (attackModeTarget != null) pathingDest = attackModeTarget;
		else if (computedBestPastrLocation != null) pathingDest = computedBestPastrLocation;
		else pathingDest = null;

		if (pathingDest != null) Bfs.work(pathingDest, rc, 9000);
	}

	private void doFirstTurn() throws GameActionException {
		MessageBoard.setDefaultChannelValues();
		spawnSoldier();

		computePastrScores();
		computeBestPastrLocation();
		MessageBoard.BEST_PASTR_LOC.writeMapLocation(computedBestPastrLocation);

		Strategy.active = pickStrategyByAnalyzingMap();
		MessageBoard.STRATEGY.writeStrategy(Strategy.active);

//		Debug.indicate("map", 2, "going with " + Strategy.active.toString());
	}

	private int guessTravelRounds(MapLocation start, MapLocation dest) {
		int ret = (int) (GameConstants.SOLDIER_MOVE_ACTION_DELAY * Math.sqrt(start.distanceSquaredTo(dest)));
		MapLocation probe = start;
		boolean inObstacle = false;
		int numObstacles = 0;
		do {
			probe = probe.add(probe.directionTo(theirHQ));
			if (rc.senseTerrainTile(probe) == TerrainTile.VOID) {
				if (!inObstacle) numObstacles++; // too big?
				inObstacle = true;
			} else {
				inObstacle = false;
			}
		} while (!probe.equals(theirHQ));
		ret += 25 * numObstacles;
		return ret;
	}

	private double estimateNearbyCowGrowth(MapLocation loc) {
		double ret = 0;
		int minX = Math.max(0, loc.x - 10);
		int minY = Math.max(0, loc.y - 10);
		int maxX = Math.min(rc.getMapWidth() - 1, loc.x + 10);
		int maxY = Math.min(rc.getMapHeight() - 1, loc.y + 10);
		for (int x = minX; x <= maxX; x++) {
			for (int y = minY; y <= maxY; y++) {
				if (rc.senseTerrainTile(new MapLocation(x, y)) != TerrainTile.VOID) ret += cowGrowth[x][y];
			}
		}
		return ret;
	}

	private int estimateTimeToWin(double totalCowGrowth) {
		double equilibriumCowPopulation = totalCowGrowth / (1 - GameConstants.NEUTRALS_TURN_DECAY);
		return (int) (GameConstants.WIN_QTY / (1 + equilibriumCowPopulation)); // avoid divide by zero!!
	}

	private double estimateHQHerdObstacleSlowdownFactor() {
		double slowdownFactor = 1;
		for (int i = 8; i-- > 0;) {
			Direction dir = Direction.values()[i];
			MapLocation loc = ourHQ;
			for (int j = 10; j-- > 0;) {
				loc = loc.add(dir);
				if (rc.senseTerrainTile(loc) == TerrainTile.VOID) {
					slowdownFactor += 0.25;
					break;
				}
			}
		}
		return slowdownFactor;
	}

	private Strategy pickStrategyByAnalyzingMap() throws GameActionException {
		// Guess how long it would take the enemy to rush a well-placed pastr
		MapLocation mapCenter = new MapLocation(rc.getMapWidth() / 2, rc.getMapHeight() / 2);
		int openPastrRushRounds = guessTravelRounds(theirHQ, mapCenter) + guessTravelRounds(mapCenter, computedBestPastrLocation);

		// my pathfinding rocks:
		int pastrTravelDelay = (int) (GameConstants.SOLDIER_MOVE_ACTION_DELAY * Math.sqrt(ourHQ.distanceSquaredTo(computedBestPastrLocation)));
		int pastrBuildDelay = (int) (GameConstants.HQ_SPAWN_DELAY_CONSTANT_1 + RobotType.PASTR.captureTurns); // how long it will take for our pastr to go up
		openPastrRushRounds += pastrBuildDelay; // they can't rush until they know where to rush to

		// Guess how many rounds it would take us to win with a noise tower in the open
		double openPastrCowGrowth = estimateNearbyCowGrowth(computedBestPastrLocation);
		int openPastrRoundsNeeded = estimateTimeToWin(openPastrCowGrowth);
		int towerInefficiency = 150; // we don't start milking immediately, unfortunately
		openPastrRoundsNeeded += towerInefficiency;
		int fastTowerBuildDelay = RobotType.NOISETOWER.captureTurns + pastrTravelDelay; // account for the time needed to go there and set up a tower
		int safeTowerBuildDelay = RobotType.NOISETOWER.captureTurns + Math.max(81, pastrTravelDelay); // account for the time needed to go there and set up a
																										// tower and wait for them to make a pastr
		int fastOpenPastrRoundsToWin = openPastrRoundsNeeded + fastTowerBuildDelay;
		int safeOpenPastrRoundsToWin = openPastrRoundsNeeded + safeTowerBuildDelay;

		// Guess how many rounds it would take us to win with a noise tower in the HQ
		double hqPastrCowGrowth = estimateNearbyCowGrowth(ourHQ);
		int hqPastrRoundsToWin = estimateTimeToWin(hqPastrCowGrowth);
		double hqSlowdown = estimateHQHerdObstacleSlowdownFactor();
		hqPastrRoundsToWin *= hqSlowdown;
		hqPastrRoundsToWin += RobotType.NOISETOWER.captureTurns;
		hqPastrRoundsToWin += towerInefficiency;

//		Debug.indicate("map", 0, String.format("fastOpenPastrRoundsToWin = %d, safeOpenPastrRoundsToWin = %d (cows = %f)", fastOpenPastrRoundsToWin,
//				safeOpenPastrRoundsToWin, openPastrCowGrowth));
//		Debug.indicate("map", 1, String.format("hqPastrRoundsToWin = %d (cows = %f, slowdown = %f), rushRounds = %d", hqPastrRoundsToWin, hqPastrCowGrowth,
//				hqSlowdown, openPastrRushRounds));

		Strategy strat;

		if (fastOpenPastrRoundsToWin < openPastrRushRounds) {
			// I can't imagine this actually happening, but if we can win before the rush even gets to us
			// then go for it!
			strat = Strategy.NOISE_THEN_ONE_PASTR;
		} else if (safeOpenPastrRoundsToWin < hqPastrRoundsToWin) {
			// otherwise we probably have to decide between a safe open pastr and an HQ pastr.
			// Go open pastr only if is significantly faster
			strat = Strategy.ONE_PASTR_THEN_NOISE;
		} else {
			strat = Strategy.HQ_PASTR;
		}

		return strat;
	}

	private void updateStrategicInfo() throws GameActionException {
		theirPastrs = rc.sensePastrLocations(them);
		numEnemyPastrs = theirPastrs.length;
		ourPastrs = rc.sensePastrLocations(us);
		numAlliedPastrs = ourPastrs.length;

		int numKillsLastTurn = MessageBoard.ROUND_KILL_COUNT.readInt();
		MessageBoard.ROUND_KILL_COUNT.writeInt(0);
		maxEnemySpawns -= numKillsLastTurn;

		virtualSpawnCountdown--;
		if (virtualSpawnCountdown <= 0) {
			maxEnemySpawns++;
			int maxEnemyPopCount = maxEnemySpawns + numEnemyPastrs;
			virtualSpawnCountdown = (int) Math.round(GameConstants.HQ_SPAWN_DELAY_CONSTANT_1
					+ Math.pow(maxEnemyPopCount - 1, GameConstants.HQ_SPAWN_DELAY_CONSTANT_2));
		}
		maxEnemySoldiers = maxEnemySpawns - numEnemyPastrs;

		numAlliedSoldiers = 0;
		Robot[] allAlliedRobots = rc.senseNearbyGameObjects(Robot.class, 999999, us);
		allAllies = new RobotInfo[allAlliedRobots.length];
		for (int i = allAlliedRobots.length; i-- > 0;) {
			RobotInfo info = rc.senseRobotInfo(allAlliedRobots[i]);
			allAllies[i] = info;
			if (info.type == RobotType.SOLDIER) numAlliedSoldiers++;
		}

		ourMilk = rc.senseTeamMilkQuantity(us);
		theirMilk = rc.senseTeamMilkQuantity(them);
	}

	private void directStrategy() throws GameActionException {
		switch (Strategy.active) {
			case NOISE_THEN_ONE_PASTR:
			case ONE_PASTR_THEN_NOISE:
				directStrategyOnePastr();
				break;

			case HQ_PASTR:
			case RUSH:
				directStrategyRush();
				break;

			case MACRO:
				break;

			default:
				System.out.println("Uh oh! Unknown strategy!");
				break;
		}
	}

	private void directStrategyRush() throws GameActionException {
		attackModeTarget = chooseEnemyPastrAttackTarget();
		if (attackModeTarget == null) { // rally toward center if there are no enemy pastrs
			attackModeTarget = new MapLocation(rc.getMapWidth() / 2, rc.getMapHeight() / 2);
			while (rc.senseTerrainTile(attackModeTarget) == TerrainTile.VOID) {
				attackModeTarget = attackModeTarget.add(attackModeTarget.directionTo(ourHQ));
			}
		}
		MessageBoard.ATTACK_LOC.writeMapLocation(attackModeTarget);
	}

	private boolean theyHavePastrOutsideHQ() {
		boolean attackablePastrExists = false;
		for (int i = theirPastrs.length; i-- > 0;) {
			if (!theirPastrs[i].isAdjacentTo(theirHQ)) {
				return true;
			}
		}
		return false;
	}

	private void directStrategyOnePastr() throws GameActionException {
		boolean desperation = false;

		// Decided whether to trigger attack mode
		if (!attackModeTriggered) {
			// if the enemy has overextended himself building pastrs, attack!
			// I think even building two pastrs is an overextension that we can punish.
			if (numEnemyPastrs >= 2) {
				// We should only try to punish them if we have at least as many soldiers as they do
				// One issue is that we may be overestimating their strength because with some probability
				// they've built a noise tower, sacrificing a soldier and slowing their spawns more than
				// we would estimate. So fudge it by 1 soldier:
				if (numAlliedSoldiers + 1 >= maxEnemySoldiers) {
					// However, we need to make sure that at least one of these pastrs is outside their
					// HQ zone. There's no point in trying to punish them for building a bunch of pastrs
					// around their HQ; it doesn't help them and we can't kill them.
					if (theyHavePastrOutsideHQ()) {
						// If all these conditions are met, then punish them!
						attackModeTriggered = true;
					}
				}
			}

			// if the enemy is out-milking us, then we need to attack or we are going to lose
			if (theirMilk >= 0.4 * GameConstants.WIN_QTY && theirMilk > ourMilk) {
				// If they just have a single pastr next to their HQ, though, attacking them won't do much
				// good. Better to just hope we out-milk them
				if (theyHavePastrOutsideHQ()) {
					attackModeTriggered = true;
					desperation = true;
				}
			}
		}

		if (attackModeTriggered) {
			attackModeTarget = chooseEnemyPastrAttackTarget();
			
			//If we don't have a pastr, and we can't kill theirs, don't try to 
			if(ourPastrs.length == 0 && attackModeTarget != null && attackModeTarget.isAdjacentTo(theirHQ)) {
				attackModeTarget = null;
			}
			
			// if there's no pastr to attack, decide whether to camp their spawn or to defend/rebuid our pastr:
			if (attackModeTarget == null) {
				// first, if our pastr has been destroyed we need to rebuild it instead of attacking.
				if (ourPastrs.length > 0) {
					// but if it's up, consider camping their spawn. Camping their spawn is only a good idea
					// if we are ahead on milk. Otherwise we should defend our pastr
					if (!desperation) {
						attackModeTarget = theirHQ;
					}
				}
			}
			MessageBoard.ATTACK_LOC.writeMapLocation(attackModeTarget);
		}
	}

	private MapLocation findSoldierCenterOfMass() {
		int x = 0;
		int y = 0;
		int N = 0;
		for (int i = allAllies.length; i-- > 0;) {
			RobotInfo ally = allAllies[i];
			if (ally.type == RobotType.SOLDIER) {
				MapLocation allyLoc = ally.location;
				x += allyLoc.x;
				y += allyLoc.y;
				N++;
			}
		}
		if (N == 0) return null;
		return new MapLocation(x / N, y / N);
	}

	private MapLocation chooseEnemyPastrAttackTarget() {
		MapLocation soldierCenter = findSoldierCenterOfMass();

		if (soldierCenter == null) soldierCenter = ourHQ;

		int bestDifficulty = 999999;
		MapLocation bestTarget = null;
		for (int i = theirPastrs.length; i-- > 0;) {
			MapLocation pastr = theirPastrs[i];
			int difficulty = pastr.distanceSquaredTo(soldierCenter); // could be as high as 10000
			int distSqToTheirHQ = pastr.distanceSquaredTo(theirHQ);
			if (distSqToTheirHQ <= 5) difficulty += 10000 * distSqToTheirHQ; // save pastrs near HQ for last
			if (difficulty < bestDifficulty) {
				bestDifficulty = difficulty;
				bestTarget = pastr;
			}
		}
		return bestTarget;
	}

	// TODO: this takes a little too long on big maps
	private void computePastrScores() {
		int mapWidth = rc.getMapWidth();
		int mapHeight = rc.getMapHeight();
		double mapSize = Math.hypot(mapWidth, mapHeight);
		MapLocation mapCenter = new MapLocation(mapWidth / 2, mapHeight / 2);

		double[][] pastrScores = new double[mapWidth][mapHeight];
		for (int y = 2; y < mapHeight - 2; y += 5) {
			for (int x = 2; x < mapWidth - 2; x += 5) {
				if (rc.senseTerrainTile(new MapLocation(x, y)) != TerrainTile.VOID) {
					MapLocation loc = new MapLocation(x, y);
					double distOurHQ = Math.sqrt(loc.distanceSquaredTo(ourHQ));
					double distTheirHQ = Math.sqrt(loc.distanceSquaredTo(theirHQ));
					if (distOurHQ < distTheirHQ) {
						int numCows = 0;
						for (int cowX = x - 2; cowX <= x + 2; cowX++) {
							for (int cowY = y - 2; cowY <= y + 2; cowY++) {
								if (rc.senseTerrainTile(new MapLocation(cowX, cowY)) != TerrainTile.VOID) {
									numCows += cowGrowth[cowX][cowY];
								}
							}
						}
						if (numCows >= 5) {
							double distCenter = Math.sqrt(loc.distanceSquaredTo(mapCenter));
							pastrScores[x][y] = numCows * (1 + (1.0 * distCenter - 0.5 * distOurHQ + 0.5 * distTheirHQ) / mapSize);
						} else {
							pastrScores[x][y] = -999999; // must be at least some cows
						}
					} else {
						pastrScores[x][y] = -999999; // only make pastrs on squares closer to our HQ than theirs
					}
				} else {
					pastrScores[x][y] = -999999; // don't make pastrs on void squares
				}
			}
		}

		computedPastrScores = pastrScores;
	}

	private void computeBestPastrLocation() {
		MapLocation bestPastrLocation = null;
		double bestPastrScore = -999;

		int mapWidth = rc.getMapWidth();
		int mapHeight = rc.getMapHeight();
		for (int x = 2; x < mapWidth - 2; x += 5) {
			for (int y = 2; y < mapHeight - 2; y += 5) {
				if (computedPastrScores[x][y] > bestPastrScore) {
					MapLocation loc = new MapLocation(x, y);
					if (!Util.contains(ourPastrs, new MapLocation(x, y))) {
						bestPastrScore = computedPastrScores[x][y];
						bestPastrLocation = loc;
					}
				}
			}
		}

		computedBestPastrLocation = bestPastrLocation;
	}

	private boolean attackEnemies() throws GameActionException {
		Robot[] enemies = rc.senseNearbyGameObjects(Robot.class, here, 25, them);
		if (enemies.length == 0) return false;

		double bestTotalDamage = 0;
		MapLocation bestTarget = null;
		for (int i = enemies.length; i-- > 0;) {
			RobotInfo info = rc.senseRobotInfo(enemies[i]);
			MapLocation enemyLoc = info.location;
			if (!Util.inHQAttackRange(enemyLoc, here)) continue;

			int distSq = here.distanceSquaredTo(enemyLoc);
			MapLocation target = enemyLoc;
			double directDamage = RobotType.HQ.attackPower - RobotType.HQ.splashPower;
			if (distSq > RobotType.HQ.attackRadiusMaxSquared) {
				target = target.add(target.directionTo(here));
				directDamage = 0;
			}

			int enemiesSplashed = rc.senseNearbyGameObjects(Robot.class, target, 2, them).length;
			int alliesSplashed = rc.senseNearbyGameObjects(Robot.class, target, 2, us).length;
			double netSplashDamage = RobotType.HQ.splashPower * (enemiesSplashed - alliesSplashed);
			double totalDamage = directDamage + netSplashDamage;
			if (totalDamage > bestTotalDamage) {
				bestTotalDamage = totalDamage;
				bestTarget = target;
			}
		}

		if (bestTarget != null) rc.attackSquare(bestTarget);
		return true;
	}

	private boolean spawnSoldier() throws GameActionException {
		if (rc.senseRobotCount() >= GameConstants.MAX_ROBOTS) return false;
		// if (rc.senseRobotCount() >= 2) return false;

		int spawnCount = MessageBoard.SPAWN_COUNT.readInt();

		Direction startDir = here.directionTo(theirHQ);
		if (startDir.isDiagonal()) startDir = startDir.rotateRight();

		int[] offsets = new int[] { 0, 2, 4, 6, 1, 3, 5, 7 };
		for (int i = 0; i < offsets.length; i++) {
			Direction dir = Direction.values()[(startDir.ordinal() + offsets[i]) % 8];
			if (rc.canMove(dir)) {
				rc.spawn(dir);
				MessageBoard.SPAWN_COUNT.writeInt(spawnCount + 1);
				return true;
			}
		}
		
		return false;
	}
}
