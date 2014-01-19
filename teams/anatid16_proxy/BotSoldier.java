package anatid16_proxy;

import battlecode.common.*;

public class BotSoldier extends Bot {
	public BotSoldier(RobotController theRC) throws GameActionException {
		super(theRC);
		Debug.init(theRC, "action");
		Nav.init(theRC);

		spawnOrder = MessageBoard.SPAWN_COUNT.readInt();
		// Debug.indicate("buildorder", 0, "I am robot #" + spawnOrder);

		Debug.indicate("proxy", 2, "my spawn order = " + spawnOrder + ", pastr builder spawn order = " + MessageBoard.PASTR_BUILDER_ROBOT_ID.readInt());
		if (spawnOrder == MessageBoard.TOWER_BUILDER_SPAWN_ORDER.readInt()) {
			MessageBoard.TOWER_BUILDER_ROBOT_ID.writeInt(rc.getRobot().getID());
			designatedToBuildTower = true;
		} else if (spawnOrder == MessageBoard.PASTR_BUILDER_SPAWN_ORDER.readInt()) {
			MessageBoard.PASTR_BUILDER_ROBOT_ID.writeInt(rc.getRobot().getID());
			designatedToBuildPastr = true;
		}
	}

	RobotInfo[] visibleEnemies; // enemies within vision radius (35)
	RobotInfo[] attackableEnemies; // enemies within attack radius(10)
	int spawnOrder;

	boolean designatedToBuildTower = false;
	boolean designatedToBuildPastr = false;

	public void turn() throws GameActionException {
		if (!rc.isActive()) return;
		if (rc.getConstructingRounds() > 0) return; // can't do anything while constructing; is this redundant?

		Strategy.active = MessageBoard.STRATEGY.readStrategy();
		if (Strategy.active == Strategy.UNDECIDED) return;

		updateEnemyData();
		MapLocation rallyLoc = MessageBoard.RALLY_LOC.readMapLocation();

		if (mercyKillPastrs()) return;

		// Do any obligatory micro
		if (visibleEnemies.length > 0 && rc.isActive()) {
			doObligatoryMicro();

			if (!rc.isActive()) {
				Debug.indicate("action", 0, "returning after obligatory micro");
				return;
			}
		}

		designatedToBuildTower = rc.getRobot().getID() == MessageBoard.TOWER_BUILDER_ROBOT_ID.readInt();
		designatedToBuildPastr = rc.getRobot().getID() == MessageBoard.PASTR_BUILDER_ROBOT_ID.readInt();

		MapLocation pastrLoc = MessageBoard.BEST_PASTR_LOC.readMapLocation();
		if (pastrLoc != null) {
			if (!designatedToBuildTower && !designatedToBuildPastr && here.equals(pastrLoc)) {
				MessageBoard.PASTR_BUILDER_ROBOT_ID.writeInt(rc.getRobot().getID());
				MessageBoard.PASTR_BUILDER_SPAWN_ORDER.writeInt(-1);
				designatedToBuildPastr = true;
			}

			if (!designatedToBuildTower && !designatedToBuildPastr && here.isAdjacentTo(pastrLoc)) {
				int towerBuilderId = MessageBoard.TOWER_BUILDER_ROBOT_ID.readInt();
				Robot[] otherAdjacentBots = rc.senseNearbyGameObjects(Robot.class, pastrLoc, 2, us);
				boolean towerBuilderIsAdjacent = false;
				for (Robot ally : otherAdjacentBots) {
					if (ally.getID() == towerBuilderId) {
						towerBuilderIsAdjacent = true;
						break;
					}
				}
				if (!towerBuilderIsAdjacent) {
					Debug.indicate("proxy", 1, "I'm adjacent and the tower builder isn't (his id is " + towerBuilderId + ")");
					MessageBoard.TOWER_BUILDER_ROBOT_ID.writeInt(rc.getRobot().getID());
					MessageBoard.TOWER_BUILDER_SPAWN_ORDER.writeInt(-1);
					designatedToBuildTower = true;
				}
			}
		}

		if (designatedToBuildTower) {
			goBuildProxyStratTower();
			return;
		}
		if (designatedToBuildPastr) {
			goBuildProxyStratPastr();
			return;
		}

		if (rallyLoc != null) {
			if (visibleEnemies.length > 0 && here.distanceSquaredTo(rallyLoc) <= 49) {
				if (doVoluntaryDefensiveMicro()) return;
			}
			MapLocation[] ourPastrs = rc.sensePastrLocations(us);
			Nav.Sneak sneak = ourPastrs.length > 0 && here.distanceSquaredTo(ourPastrs[0]) <= 49 ? Nav.Sneak.YES : Nav.Sneak.NO;
			Debug.indicate("action", 0, "naving to rally loc");
			Nav.goTo(rallyLoc, sneak, Nav.Engage.YES);
		}
	}

	private void goBuildProxyStratTower() throws GameActionException {
		Debug.indicate("proxy", 0, "going to build proxy strat tower");
		MapLocation pastrLoc = MessageBoard.BEST_PASTR_LOC.readMapLocation();
		if (here.isAdjacentTo(pastrLoc)) {
			constructAndAdvertiseNoiseTower(pastrLoc);
		} else {
			Nav.goTo(pastrLoc, Nav.Sneak.NO, Nav.Engage.NO);
		}
	}

	private void goBuildProxyStratPastr() throws GameActionException {
		Debug.indicate("proxy", 0, "going to build proxy strat pastr");
		MapLocation pastrLoc = MessageBoard.BEST_PASTR_LOC.readMapLocation();
		if (here.equals(pastrLoc)) {
			int noiseTowerBuildStartRound = MessageBoard.NOISE_TOWER_BUILD_START_ROUND.readInt();
			if (noiseTowerBuildStartRound > 0 && Clock.getRoundNum() - noiseTowerBuildStartRound > 80) {
				rc.construct(RobotType.PASTR);
				return;
			}
			Debug.indicate("proxy", 1, "finessing build time");
			return;
		} else {
			Nav.goTo(pastrLoc, Nav.Sneak.NO, Nav.Engage.NO);
		}
	}

	private void constructAndAdvertiseNoiseTower(MapLocation pastrLoc) throws GameActionException {
		MessageBoard.NOISE_TOWER_BUILD_LOCATION.writeMapLocation(here);
		MessageBoard.NOISE_TOWER_BUILD_START_ROUND.writeInt(Clock.getRoundNum());
		rc.construct(RobotType.NOISETOWER);
		HerdPattern.computeAndPublish(here, pastrLoc, rc);
	}

	private void updateEnemyData() throws GameActionException {
		Robot[] visibleEnemyRobots = rc.senseNearbyGameObjects(Robot.class, RobotType.SOLDIER.sensorRadiusSquared, them);

		visibleEnemies = new RobotInfo[visibleEnemyRobots.length];

		boolean[] attackable = new boolean[visibleEnemyRobots.length];
		int numAttackableEnemies = 0;
		for (int i = visibleEnemies.length; i-- > 0;) {
			RobotInfo info = rc.senseRobotInfo(visibleEnemyRobots[i]);
			visibleEnemies[i] = info;
			if (here.distanceSquaredTo(info.location) <= RobotType.SOLDIER.attackRadiusMaxSquared) {
				numAttackableEnemies++;
				attackable[i] = true;
			}
		}
		attackableEnemies = new RobotInfo[numAttackableEnemies];
		int enemyCounter = 0;
		for (int i = visibleEnemies.length; i-- > 0;) {
			if (attackable[i]) attackableEnemies[enemyCounter++] = visibleEnemies[i];
		}
	}

	private boolean doVoluntaryDefensiveMicro() throws GameActionException {
		// We aren't in combat. This function just moves cautiously toward the nearest enemy.
		MapLocation closestEnemy = Util.closestNonHQ(visibleEnemies, rc);
		return tryMoveTowardLocationWithMaxEnemyExposure(closestEnemy, 0, Nav.Sneak.NO);
	}

	// obligatory micro is attacks or movements that we have to do because either we or a nearby ally is in combat
	private boolean doObligatoryMicro() throws GameActionException {
		int numSoldiersAttackingUs = Util.countNonConstructingSoldiers(attackableEnemies);
		if (numSoldiersAttackingUs >= 1) {
			// we are in combat
			// If we are getting double-teamed worse than any of the enemies we are fighting, try to retreat
			int maxAlliesAttackingEnemy = 0;
			for (int i = attackableEnemies.length; i-- > 0;) {
				// int numAlliesAttackingEnemy = 1 + numOtherAlliedSoldiersInAttackRange(attackableEnemies[i].location);
				// we deliberately include buildings in this count to encourage our soldiers to defend buildings:
				int numAlliesAttackingEnemy = 1 + numOtherAlliedSoldiersAndBuildingsInAttackRange(attackableEnemies[i].location);
				if (numAlliesAttackingEnemy > maxAlliesAttackingEnemy) maxAlliesAttackingEnemy = numAlliesAttackingEnemy;
			}
			if (numSoldiersAttackingUs == 1) {
				if (maxAlliesAttackingEnemy == 1) {
					// we are in a 1v1. fight if we are winning, otherwise retreat
					RobotInfo singleEnemy = Util.findANonConstructingSoldier(attackableEnemies);
					if (rc.getHealth() >= singleEnemy.health) {
						attackAndRecord(singleEnemy);
					} else {
						retreatOrFight();
					}
				} else {
					// we outnumber the lone enemy. kill him
					RobotInfo singleEnemy = Util.findANonConstructingSoldier(attackableEnemies);
					attackAndRecord(singleEnemy);
				}
			} else if (numSoldiersAttackingUs > maxAlliesAttackingEnemy) {
				// We are getting doubled teamed too badly.
				retreatOrFight();
			} else {
				// Several enemies can attack us, but we are also double teaming the enemy badly enough to keep fighting
				Debug.indicate("micro", 0, "attacking");
				attackANonConstructingSoldier();
			}
			return true;
		} else {
			// we are not in combat. We are only obligated to do something here if a nearby ally is in combat and
			// we can attack a soldier who is attacking them. Then we have to decide whether to move to attack such a soldier
			// and if so which one. Actually, there is one other thing that is obligatory, which is to move to attack
			// helpless enemies (buildings and constructing soldiers) if it is safe to do so.
			MapLocation closestEnemySoldier = Util.closestNonConstructingSoldier(visibleEnemies, here);
			if (closestEnemySoldier != null) {
				// int numAlliesFighting = numOtherAlliedSoldiersInAttackRange(closestEnemySoldier);
				// we deliberately include buildings in this count to encourage our soldiers to defend buildings:
				int numAlliesFighting = numOtherAlliedSoldiersAndBuildingsInAttackRange(closestEnemySoldier);
				if (numAlliesFighting > 0) {
					// Approach this enemy if doing so would expose us to at most numAlliesFighting enemies.
					if (tryMoveTowardLocationWithMaxEnemyExposure(closestEnemySoldier, numAlliesFighting, Nav.Sneak.NO)) {
						Debug.indicate("micro", 0, "moving to support allies fighting closest enemy (max enemy exposure = " + numAlliesFighting + ")");
						return true;
					}
				}
			}

			// If we didn't have to go help an ally, or were unable to, check if there is something nearby we can shoot.
			// If so, shoot it. It must be a constructing solder or building because it's not a non-constructing soldier
			if (attackableEnemies.length > 0) {
				Debug.indicate("micro", 0, "attacking a helpless enemy");
				attackAHelplessEnemy();
				return true;
			}

			// We didn't have to help an ally or shoot a helpless enemy. One final possibility with an obligatory response:
			// if we can engage a helpless enemy and not get shot, do it
			if (tryMoveToEngageUndefendedHelplessEnemy()) {
				Debug.indicate("micro", 0, "moving to engage a helpless enemy");
				return true;
			}

			// If none of the above cases compelled us to action, there is no obligatory action
			return false;
		}
	}

	private boolean tryMoveToEngageUndefendedHelplessEnemy() throws GameActionException {
		visibleEnemyLoop: for (int i = visibleEnemies.length; i-- > 0;) {
			RobotInfo enemy = visibleEnemies[i];
			if (Util.isHelpless(enemy)) {
				MapLocation enemyLocation = enemy.location;
				Debug.indicate("micro", 1, "see helpless enemy at " + enemyLocation.toString());
				Direction toEnemyDir = here.directionTo(enemyLocation);
				if (!rc.canMove(toEnemyDir)) continue visibleEnemyLoop;
				MapLocation pathLoc = here;
				while (pathLoc.distanceSquaredTo(enemyLocation) > RobotType.SOLDIER.attackRadiusMaxSquared) {
					pathLoc = pathLoc.add(pathLoc.directionTo(enemyLocation));
					if (rc.senseTerrainTile(pathLoc) == TerrainTile.VOID) continue visibleEnemyLoop;
					if (Util.inHQAttackRange(pathLoc, theirHQ)) continue visibleEnemyLoop;
					Robot[] enemyRobotsAlongPath = rc.senseNearbyGameObjects(Robot.class, pathLoc, RobotType.SOLDIER.attackRadiusMaxSquared, them);
					if (Util.containsNonConstructingSoldier(enemyRobotsAlongPath, rc)) continue visibleEnemyLoop;
				}

				// the direct path to attack this helpless enemy is free of enemy soldiers. start moving along it
				rc.move(toEnemyDir);
				return true;
			}
		}
		return false;
	}

	private boolean tryRepositionToAttackTarget(MapLocation target, int maxEnemyExposure) throws GameActionException {
		int[] numEnemiesAttackingDirs = countNumEnemiesAttackingMoveDirs();

		Direction bestRepositionDir = null;
		int bestNumSoldiersAttackingUs = 999;
		for (int i = 8; i-- > 0;) {
			Direction tryDir = Direction.values()[i];
			if (!rc.canMove(tryDir)) continue;
			MapLocation tryLoc = here.add(tryDir);
			if (tryLoc.distanceSquaredTo(target) > RobotType.SOLDIER.attackRadiusMaxSquared) continue;
			if (Util.inHQAttackRange(tryLoc, theirHQ)) continue;

			int numSoldiersAttackingUs = numEnemiesAttackingDirs[i];
			if (numSoldiersAttackingUs > maxEnemyExposure) continue;

			if (numSoldiersAttackingUs < bestNumSoldiersAttackingUs) {
				bestNumSoldiersAttackingUs = numSoldiersAttackingUs;
				bestRepositionDir = tryDir;
			}
		}

		if (bestRepositionDir == null) return false;

		rc.move(bestRepositionDir);
		return true;
	}

	private boolean tryMoveTowardLocationWithMaxEnemyExposure(MapLocation dest, int maxEnemyExposure, Nav.Sneak sneak) throws GameActionException {
		int[] numEnemiesAttackingDirs = countNumEnemiesAttackingMoveDirs();

		Direction toEnemy = here.directionTo(dest);
		Direction[] tryDirs = new Direction[] { toEnemy, toEnemy.rotateLeft(), toEnemy.rotateRight() };
		for (int i = 0; i < tryDirs.length; i++) {
			Direction tryDir = tryDirs[i];
			if (!rc.canMove(tryDir)) continue;
			if (numEnemiesAttackingDirs[tryDir.ordinal()] > maxEnemyExposure) continue;
			if (Util.inHQAttackRange(here.add(tryDir), theirHQ)) continue;
			Debug.indicate("micro", 1, String.format("moving toward %s with max enemy exposure %d (actual exposure %d)", dest.toString(), maxEnemyExposure,
					numEnemiesAttackingDirs[tryDir.ordinal()]));
			if (sneak == Nav.Sneak.YES) rc.move(tryDir);
			else rc.sneak(tryDir);
			return true;
		}
		Debug.indicate("micro", 1, String.format("can't move toward %s with max enemy exposure %d", dest.toString(), maxEnemyExposure));
		return false;
	}

	private boolean tryMoveDirectlyTowardLocationWithMaxEnemyExposure(MapLocation dest, int maxEnemyExposure) throws GameActionException {
		int[] numEnemiesAttackingDirs = countNumEnemiesAttackingMoveDirs();

		Direction tryDir = here.directionTo(dest);
		if (!rc.canMove(tryDir)) return false;
		if (numEnemiesAttackingDirs[tryDir.ordinal()] > maxEnemyExposure) return false;
		if (Util.inHQAttackRange(here.add(tryDir), theirHQ)) return false;
		Debug.indicate("micro", 1, String.format("moving directly toward %s with max enemy exposure %d (actual exposure %d)", dest.toString(),
				maxEnemyExposure, numEnemiesAttackingDirs[tryDir.ordinal()]));
		rc.move(tryDir);
		return true;
	}

	int[] shootX = new int[] { -1, 0, 1, -2, -1, 0, 1, 2, -2, -1, 1, 2, -2, -1, 0, 1, 2, -1, 0, 1 };
	int[] shootY = new int[] { 2, 2, 2, 1, 1, 1, 1, 1, 0, 0, 0, 0, -1, -1, -1, -1, -1, -2, -2, -2 };

	private boolean tryToKillCows() throws GameActionException {
		MapLocation bestTarget = null;
		double mostCows = -1;
		for (int i = shootX.length; i-- > 0;) {
			MapLocation target = here.add(shootX[i], shootY[i]);
			double cows = rc.senseCowsAtLocation(target);
			if (cows > mostCows) {
				if (numOtherAlliedSoldiersInRange(target, 1) == 0) {
					mostCows = cows;
					bestTarget = target;
				}
			}
		}
		Debug.indicate("micro", 2, "trying to kill cows: mostCows = " + mostCows + " at " + (bestTarget == null ? "null" : bestTarget.toString()));
		if (mostCows > 300) {
			rc.attackSquare(bestTarget);
			return true;
		} else {
			return false;
		}
	}

	private int numOtherAlliedSoldiersAndBuildingsInAttackRange(MapLocation loc) {
		return rc.senseNearbyGameObjects(Robot.class, loc, RobotType.SOLDIER.attackRadiusMaxSquared, us).length;
	}

	private int numOtherAlliedSoldiersInAttackRange(MapLocation loc) throws GameActionException {
		return numOtherAlliedSoldiersInRange(loc, RobotType.SOLDIER.attackRadiusMaxSquared);
	}

	private int numOtherAlliedSoldiersInRange(MapLocation loc, int rangeSq) throws GameActionException {
		int numAlliedSoldiers = 0;
		Robot[] allies = rc.senseNearbyGameObjects(Robot.class, loc, rangeSq, us);
		for (int i = allies.length; i-- > 0;) {
			if (rc.senseRobotInfo(allies[i]).type == RobotType.SOLDIER) numAlliedSoldiers++;
		}
		return numAlliedSoldiers;
	}

	// Includes buildings!
	private int numOtherAlliedUnitsInAttackRange(MapLocation loc) throws GameActionException {
		return numOtherAlliedUnitsInRange(loc, RobotType.SOLDIER.attackRadiusMaxSquared);
	}

	// Includes buildings!
	private int numOtherAlliedUnitsInRange(MapLocation loc, int rangeSq) throws GameActionException {
		Robot[] allies = rc.senseNearbyGameObjects(Robot.class, loc, rangeSq, us);
		return allies.length;
	}

	// Assumes attackableEnemies contains a non-constructing soldier
	private void attackANonConstructingSoldier() throws GameActionException {
		RobotInfo target = chooseNonConstructingSoldierAttackTarget(attackableEnemies);
		attackAndRecord(target);
	}

	// Assumes enemies list contains at least one non-constructing soldier!
	private RobotInfo chooseNonConstructingSoldierAttackTarget(RobotInfo[] enemies) throws GameActionException {
		RobotInfo ret = null;
		double bestNumNearbyAllies = -1;
		double bestHealth = 999999;
		double bestActionDelay = 999999;
		for (int i = enemies.length; i-- > 0;) {
			RobotInfo info = enemies[i];
			if (info.type != RobotType.SOLDIER || info.isConstructing) continue;
			MapLocation enemyLoc = info.location;
			int numNearbyAllies = numOtherAlliedSoldiersInAttackRange(enemyLoc);
			if (numNearbyAllies > bestNumNearbyAllies) {
				bestNumNearbyAllies = numNearbyAllies;
				bestHealth = info.health;
				bestActionDelay = info.actionDelay;
				ret = info;
			} else if (numNearbyAllies == bestNumNearbyAllies) {
				double health = info.health;
				if (health < bestHealth) {
					bestHealth = health;
					bestActionDelay = info.actionDelay;
					ret = info;
				} else if (health == bestHealth) {
					double actionDelay = info.actionDelay;
					if (actionDelay < bestActionDelay) {
						bestActionDelay = actionDelay;
						ret = info;
					}
				}
			}
		}
		return ret;
	}

	// Assumes attackableEnemies is non-empty and only contains helpless enemies
	private void attackAHelplessEnemy() throws GameActionException {
		RobotInfo target = chooseHelplessAttackTarget();
		attackAndRecord(target);
	}

	// If we can only attack buildings or constructing soldiers, this function decides which one to attack.
	// It assumes that the enemies list does not contain any non-constructing soldiers or HQs!
	private RobotInfo chooseHelplessAttackTarget() throws GameActionException {
		RobotInfo ret = null;
		double bestHealth = 999999;
		RobotType bestType = RobotType.SOLDIER;
		for (int i = attackableEnemies.length; i-- > 0;) {
			RobotInfo info = attackableEnemies[i];
			RobotType type = info.type;
			if (type == bestType) { // break ties between equal types by health
				double health = info.health;
				if (health < bestHealth) {
					bestHealth = health;
					ret = info;
				}
			} else if (type == RobotType.PASTR || bestType == RobotType.SOLDIER) { // prefer pastrs to noise towers to constructing soldiers
				bestType = type;
				bestHealth = info.health;
				ret = info;
			}
		}
		return ret;
	}

	private void retreatOrFight() throws GameActionException {
		// If all our opponents have really high action delay, we can fire a last shot
		// and still be able to move before they can return fire. This would most probably
		// happen if an enemy engaged us after several diagonal moves. This could turn
		// a losing 1v1 into a winning one!
		boolean fireOneLastShot = true;
		for (int i = attackableEnemies.length; i-- > 0;) {
			fireOneLastShot &= attackableEnemies[i].actionDelay >= 3.0 || attackableEnemies[i].constructingRounds > 0;
			if (!fireOneLastShot) break;
		}

		if (fireOneLastShot) {
			Debug.indicate("micro", 2, "parthian shot");
			attackANonConstructingSoldier();
			return;
		}

		Direction dir = chooseRetreatDirection();
		if (dir == null) { // Can't retreat! Fight!
			Debug.indicate("micro", 2, "couldn't retreat; fighting instead");
			RobotInfo target = chooseNonConstructingSoldierAttackTarget(attackableEnemies);
			attackAndRecord(target);
		} else { // Can retreat. Do it!
			Debug.indicate("micro", 2, "retreating successfully");
			rc.move(dir);
		}
	}

	// @formatter:off
	// attackNotes[5+dx][5+dy] is a list of integers dir such that a soldier at here + (dx, dy) can attack the square here.add(Direction.values()[dir])
	// It was generated by this code:
	//
	// void tmp() {
	//	MapLocation center = new MapLocation(0, 0);
	//	for(int ex = -5; ex <= +5; ex++) {
	//		System.out.print("{");
	//		for(int ey = -5; ey <= +5; ey++) {
	//			MapLocation enemyLoc = new MapLocation(ex, ey);
	//			ArrayList<Integer> attacked = new ArrayList<Integer>();
	//			for(int dir = 0; dir < 8; dir++) {
	//				MapLocation moveLoc = center.add(Direction.values()[dir]);
	//				if(moveLoc.distanceSquaredTo(enemyLoc) <= 10) attacked.add(dir);
	//			}
	//			System.out.print("{");
	//			for(int i = 0; i < attacked.size(); i++) {
	//				System.out.print(attacked.get(i));
	//				if(i < attacked.size() - 1) System.out.print(",");
	//			}
	//			System.out.print("}");
	//			if(ey < +5) {
	//				System.out.print(",");				
	//				int spaces = Math.min(16, 17 - 2*attacked.size());
	//				for(int i = 0; i < spaces; i++) System.out.print(" ");
	//			}
	//		}
	//		System.out.println("}");
	//	}
	// }
	private static int[][][] attackNotes = {{{},                {},                {},                {},                {},                {},                {},                {},                {},                {},                {}},
                                            {{},                {},                {},                {7},               {6,7},             {5,6,7},           {5,6},             {5},               {},                {},                {}},
                                            {{},                {},                {7},               {0,6,7},           {0,5,6,7},         {0,4,5,6,7},       {4,5,6,7},         {4,5,6},           {5},               {},                {}},
                                            {{},                {7},               {0,6,7},           {0,1,5,6,7},       {0,1,2,4,5,6,7},   {0,1,2,3,4,5,6,7}, {0,2,3,4,5,6,7},   {3,4,5,6,7},       {4,5,6},           {5},               {}},
                                            {{},                {0,7},             {0,1,6,7},         {0,1,2,4,5,6,7},   {0,1,2,3,4,5,6,7}, {0,1,2,3,4,5,6,7}, {0,1,2,3,4,5,6,7}, {0,2,3,4,5,6,7},   {3,4,5,6},         {4,5},             {}},
                                            {{},                {0,1,7},           {0,1,2,6,7},       {0,1,2,3,4,5,6,7}, {0,1,2,3,4,5,6,7}, {0,1,2,3,4,5,6,7}, {0,1,2,3,4,5,6,7}, {0,1,2,3,4,5,6,7}, {2,3,4,5,6},       {3,4,5},           {}},
                                            {{},                {0,1},             {0,1,2,7},         {0,1,2,3,4,6,7},   {0,1,2,3,4,5,6,7}, {0,1,2,3,4,5,6,7}, {0,1,2,3,4,5,6,7}, {0,1,2,3,4,5,6},   {2,3,4,5},         {3,4},             {}},
                                            {{},                {1},               {0,1,2},           {0,1,2,3,7},       {0,1,2,3,4,6,7},   {0,1,2,3,4,5,6,7}, {0,1,2,3,4,5,6},   {1,2,3,4,5},       {2,3,4},           {3},               {}},
                                            {{},                {},                {1},               {0,1,2},           {0,1,2,3},         {0,1,2,3,4},       {1,2,3,4},         {2,3,4},           {3},               {},                {}},
                                            {{},                {},                {},                {1},               {1,2},             {1,2,3},           {2,3},             {3},               {},                {},                {}},
                                            {{},                {},                {},                {},                {},                {},                {},                {},                {},                {},                {}}};
   	// @formatter:on

	private int[] countNumEnemiesAttackingMoveDirs() {
		int[] numEnemiesAttackingDir = new int[8];
		for (int i = visibleEnemies.length; i-- > 0;) {
			RobotInfo info = visibleEnemies[i];
			if (info.type == RobotType.SOLDIER && info.constructingRounds == 0) {
				MapLocation enemyLoc = visibleEnemies[i].location;
				int[] attackedDirs = attackNotes[5 + enemyLoc.x - here.x][5 + enemyLoc.y - here.y];
				for (int j = attackedDirs.length; j-- > 0;) {
					numEnemiesAttackingDir[attackedDirs[j]]++;
				}
			}
		}
		return numEnemiesAttackingDir;
	}

	private Direction chooseRetreatDirection() throws GameActionException {
		int repelX = 0;
		int repelY = 0;
		for (int i = visibleEnemies.length; i-- > 0;) {
			Direction repelDir = visibleEnemies[i].location.directionTo(here);
			repelX += repelDir.dx;
			repelY += repelDir.dy;
		}
		int absRepelX = Math.abs(repelX);
		int absRepelY = Math.abs(repelY);
		Direction retreatDir;
		if (absRepelX >= 1.5 * absRepelY) {
			retreatDir = repelX > 0 ? Direction.EAST : Direction.WEST;
		} else if (absRepelY >= 1.5 * absRepelX) {
			retreatDir = repelY > 0 ? Direction.SOUTH : Direction.NORTH;
		} else if (repelX > 0) {
			retreatDir = repelY > 0 ? Direction.SOUTH_EAST : Direction.NORTH_EAST;
		} else {
			retreatDir = repelY > 0 ? Direction.SOUTH_WEST : Direction.NORTH_WEST;
		}

		// Test to see if retreatDir, or either of the adjacent directions, actually work
		// To work, three conditions have to be satisfied:
		// (a) we have to be able to move in that direction
		// (b) moving in that direction has to take us out of range of enemy attacks
		// (c) moving in that direction can't take us within range of the enemy HQ
		int bestMinEnemyDistSq = 999999;
		for (int j = visibleEnemies.length; j-- > 0;) {
			int enemyDistSq = here.distanceSquaredTo(visibleEnemies[j].location);
			if (enemyDistSq < bestMinEnemyDistSq) bestMinEnemyDistSq = enemyDistSq;
		}
		Direction bestDir = null;
		int[] tryDirs = new int[] { 0, 1, -1, 2, -2, 3, -3, 4 };
		for (int i = 0; i < tryDirs.length; i++) {
			Direction tryDir = Direction.values()[(retreatDir.ordinal() + tryDirs[i] + 8) % 8];
			if (!rc.canMove(tryDir)) continue;
			MapLocation tryLoc = here.add(tryDir);
			if (Util.inHQAttackRange(tryLoc, theirHQ)) continue;

			int minEnemyDistSq = 999999;
			for (int j = visibleEnemies.length; j-- > 0;) {
				int enemyDistSq = tryLoc.distanceSquaredTo(visibleEnemies[j].location);
				if (enemyDistSq < minEnemyDistSq) minEnemyDistSq = enemyDistSq;
			}
			if (minEnemyDistSq > RobotType.SOLDIER.attackRadiusMaxSquared) return tryDir; // we can escape!!
			if (minEnemyDistSq > bestMinEnemyDistSq) {
				bestMinEnemyDistSq = minEnemyDistSq;
				bestDir = tryDir;
			}
		}

		return bestDir;
	}

	// If one of our pastrs is within our attack range and is about to die, kill it so that the
	// other team doesn't get milk for it.
	private boolean mercyKillPastrs() throws GameActionException {
		Robot[] allies = rc.senseNearbyGameObjects(Robot.class, RobotType.SOLDIER.attackRadiusMaxSquared, us);
		for (int i = allies.length; i-- > 0;) {
			RobotInfo info = rc.senseRobotInfo(allies[i]);
			if (info.type == RobotType.PASTR) {
				Debug.indicate("mercy", 0, "pastr health = " + info.health);
				if (info.health <= RobotType.SOLDIER.attackPower) {
					rc.attackSquare(info.location);
					return true;
				}
			}
		}
		return false;
	}

	private void attackAndRecord(RobotInfo enemyInfo) throws GameActionException {
		if (enemyInfo == null) return; // should never happen, but just to be sure
		rc.attackSquare(enemyInfo.location);
		if (enemyInfo.health <= RobotType.SOLDIER.attackPower) MessageBoard.ROUND_KILL_COUNT.incrementInt();
	}
}
