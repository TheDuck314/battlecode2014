package anatid20_oldmicro;

import battlecode.common.*;

public class BotSoldier extends Bot {
	public static void loop(RobotController theRC) throws Exception {
		init(theRC);
		while (true) {
			try {
				turn();
			} catch (Exception e) {
				e.printStackTrace();
			}
			rc.yield();
		}
	}

	protected static void init(RobotController theRC) throws GameActionException {
		Bot.init(theRC);
		Debug.init(theRC, "stance");
		Nav.init(theRC);

		spawnOrder = MessageBoard.SPAWN_COUNT.readInt();
		// Debug.indicate("buildorder", 0, "I am robot #" + spawnOrder);

		tryReceivePastrLocations();
		tryClaimBuildAssignment();
		// Debug.indicate("assign", 0, "after constructor, towerBuildAssignmentIndex = " + towerBuildAssignmentIndex + ", pastrBuildAssignmentIndex = "
		// + pastrBuildAssignmentIndex);
	}

	static MapLocation here;
	static RobotInfo[] visibleEnemies; // enemies within vision radius (35)
	static RobotInfo[] attackableEnemies; // enemies within attack radius(10)
	static int[] cachedNumEnemiesAttackingMoveDirs; // computed in doObligatoryMicro

	static int spawnOrder;

	public enum MicroStance {
		DEFENSIVE,
		AGGRESSIVE,
	}

	static MicroStance stance; // how to behave in combat
	static Nav.Engage navEngage; // whether to engage enemies during navigation

	static int numPastrLocations = 0;
	static MapLocation[] bestPastrLocations = new MapLocation[BotHQ.MAX_PASTR_LOCATIONS];
	static int towerBuildAssignmentIndex = -1;
	static int pastrBuildAssignmentIndex = -1;
	static boolean isFirstTurn = true;

	private static void turn() throws GameActionException {
		Debug.debug_bytecodes_init();
		here = rc.getLocation();

		if (!rc.isActive()) return;

		if (Strategy.active == Strategy.UNDECIDED) {
			Strategy.active = MessageBoard.STRATEGY.readStrategy();
			if (Strategy.active == Strategy.UNDECIDED) return; // don't do anything until we know the strategy
		}

		// Don't do anything until we get the list of pastr locations
		if (numPastrLocations == 0) {
			if (!tryReceivePastrLocations()) return;
		}

		if (MessageBoard.PASTR_DISTRESS_SIGNAL.readInt() >= Clock.getRoundNum() - 1) {
			if (mercyKillPastrs()) return;
		}

		updateEnemyData();

		// If there are enemies in attack range, fight!
		if (attackableEnemies.length > 0) {
			fight();
			return;
		}

		if (tryBuildSomething()) {
			Debug.debug_bytecodes("after tryBuild (returning)");
			return;
		}

		// Check if someone else has taken our job from us:
		if (!isFirstTurn) {
			if (towerBuildAssignmentIndex != -1) {
				if (!MessageBoard.TOWER_BUILDER_ROBOT_IDS.checkIfIOwnAssignment(towerBuildAssignmentIndex)) towerBuildAssignmentIndex = -1;
			}
			if (pastrBuildAssignmentIndex != -1) {
				if (!MessageBoard.PASTR_BUILDER_ROBOT_IDS.checkIfIOwnAssignment(pastrBuildAssignmentIndex)) pastrBuildAssignmentIndex = -1;
			}
		}
		isFirstTurn = false;

		int destIndex = towerBuildAssignmentIndex;
		if (destIndex == -1) destIndex = pastrBuildAssignmentIndex;
		if (destIndex != -1) {
			// Debug.indicate("assign", 1, "going to pastr build location " + destIndex);
			Nav.goTo(bestPastrLocations[destIndex], Nav.Sneak.NO, Nav.Engage.NO, countNumEnemiesAttackingMoveDirs());
			return;
		}

		MapLocation rallyLoc = MessageBoard.RALLY_LOC.readMapLocation();
		stance = chooseMicroStance(rallyLoc);
		Debug.indicate("stance", 0, "stance = " + stance.toString());
		navEngage = stance == MicroStance.AGGRESSIVE ? Nav.Engage.YES : Nav.Engage.NO;

		if (rallyLoc != null) {
			if (here.distanceSquaredTo(rallyLoc) <= 36) {
				if (visibleEnemies.length > 0) {
					fight();
					if (rc.isActive() && weAreNearEnemyPastr()) tryToKillCows();
				} else {
					if (rc.isActive() && weAreNearEnemyPastr()) tryToKillCows();
					Nav.goTo(rallyLoc, Nav.Sneak.NO, Nav.Engage.NO, countNumEnemiesAttackingMoveDirs());
				}
			} else {
				Nav.goTo(rallyLoc, Nav.Sneak.NO, navEngage, countNumEnemiesAttackingMoveDirs());
				if (rc.isActive() && visibleEnemies.length > 0) fight();
			}
		}
	}

	// Decide whether to behave aggressively or defensively. Only be aggressive if we are in attack mode
	// and there is a decent number of allies around, or if we are in any mode and we have a big numbers advantage
	private static MicroStance chooseMicroStance(MapLocation rallyLoc) throws GameActionException {
		if (visibleEnemies.length == 0) {
			return MicroStance.AGGRESSIVE; // stance doesn't matter if there are no enemies
		} else {
			int numAllies = 1; // us
			numAllies += Math.max(numOtherAlliedSoldiersInRange(here, RobotType.SOLDIER.sensorRadiusSquared),
					numOtherAlliedSoldiersInRange(Util.closest(visibleEnemies, here), 16));

			if (rallyLoc == null || !MessageBoard.BE_AGGRESSIVE.readBoolean()) {
				if (numAllies >= visibleEnemies.length * 2 || numAllies > visibleEnemies.length + 3) return MicroStance.AGGRESSIVE;
				else return MicroStance.DEFENSIVE;
			} else {
				if (numAllies >= 2 && numAllies >= visibleEnemies.length - 1) return MicroStance.AGGRESSIVE;
				else return MicroStance.DEFENSIVE;
			}
		}
	}

	// returns false if turn() should return, otherwise true
	private static boolean tryReceivePastrLocations() throws GameActionException {
		numPastrLocations = MessageBoard.NUM_PASTR_LOCATIONS.readInt();
		if (numPastrLocations == 0) {
			if (Strategy.active == Strategy.PROXY || Strategy.active == Strategy.PROXY_ATTACK) return true;
			else return false;
		}

		for (int i = 0; i < numPastrLocations; i++) {
			bestPastrLocations[i] = MessageBoard.BEST_PASTR_LOCATIONS.readFromMapLocationList(i);
		}

		if (Strategy.active == Strategy.ONE_PASTR || Strategy.active == Strategy.SCATTER) {
			tryClaimBuildAssignment();
		}
		return true;
	}

	private static void tryClaimBuildAssignment() throws GameActionException {
		// See if we can assign ourselves to a job:
		for (int i = 0; i < numPastrLocations; i++) {
			if (MessageBoard.TOWER_BUILDER_ROBOT_IDS.checkIfAssignmentUnowned(i)) {
				MessageBoard.TOWER_BUILDER_ROBOT_IDS.claimAssignment(i);
				towerBuildAssignmentIndex = i;
				break;
			}
		}
		if (towerBuildAssignmentIndex == -1) {
			for (int i = 0; i < numPastrLocations; i++) {
				if (MessageBoard.PASTR_BUILDER_ROBOT_IDS.checkIfAssignmentUnowned(i)) {
					MessageBoard.PASTR_BUILDER_ROBOT_IDS.claimAssignment(i);
					pastrBuildAssignmentIndex = i;
					break;
				}
			}
		}
	}

	private static boolean tryBuildSomething() throws GameActionException {
		for (int i = 0; i < numPastrLocations; i++) {
			MapLocation pastrLoc = bestPastrLocations[i];
			if (here.equals(pastrLoc)) {
				// claim the pastr build job if someone else thought they were going to do it
				if (!MessageBoard.PASTR_BUILDER_ROBOT_IDS.checkIfIOwnAssignment(i)) {
					MessageBoard.PASTR_BUILDER_ROBOT_IDS.claimAssignment(i);
				}
				if (Util.containsNoiseTower(rc.senseNearbyGameObjects(Robot.class, pastrLoc, 2, us), rc)) {
					// Debug.indicate("build", 0, "building pastr!");
					rc.construct(RobotType.PASTR);
				}
				return true;
			}

			if (here.isAdjacentTo(pastrLoc)) {
				if (!Util.containsConstructingRobotOrNoiseTower(rc.senseNearbyGameObjects(Robot.class, pastrLoc, 2, us), rc)) {
					// claim the tower build job if someone else thought they were going to do it
					if (!MessageBoard.TOWER_BUILDER_ROBOT_IDS.checkIfAssignmentUnowned(i)) {
						MessageBoard.TOWER_BUILDER_ROBOT_IDS.claimAssignment(i);
					}
					constructNoiseTower(pastrLoc);
					return true;
				}
			}
		}
		return false;
	}

	private static void constructNoiseTower(MapLocation pastrLoc) throws GameActionException {
		rc.construct(RobotType.NOISETOWER);
		HerdPattern.computeAndPublish(here, pastrLoc, rc);
	}

	private static void updateEnemyData() throws GameActionException {
		Robot[] visibleEnemyRobots = rc.senseNearbyGameObjects(Robot.class, RobotType.SOLDIER.sensorRadiusSquared, them);

		visibleEnemies = new RobotInfo[visibleEnemyRobots.length];
		for (int i = visibleEnemyRobots.length; i-- > 0;) {
			visibleEnemies[i] = rc.senseRobotInfo(visibleEnemyRobots[i]);
		}

		if (visibleEnemies.length > 0) {
			Robot[] attackableEnemyRobots = rc.senseNearbyGameObjects(Robot.class, RobotType.SOLDIER.attackRadiusMaxSquared, them);
			attackableEnemies = new RobotInfo[attackableEnemyRobots.length];
			for (int i = attackableEnemies.length; i-- > 0;) {
				RobotInfo info = rc.senseRobotInfo(attackableEnemyRobots[i]);
				attackableEnemies[i] = info;
			}
		} else {
			attackableEnemies = new RobotInfo[0];
		}

		// clear the cached value of this array at the beginning of each turn:
		cachedNumEnemiesAttackingMoveDirs = null;
	}

	private static void fight() throws GameActionException {
		Debug.indicate("micro", 0, String.format("numVisibleEnemies = %d; numAttackableEnemies = %d", visibleEnemies.length, attackableEnemies.length));

		if (attackableEnemies.length != 0) { // There is at least one enemy in attack range (which should hopefully not be their HQ)
			int numAttackableSoldiers = Util.countNonConstructingSoldiers(attackableEnemies);
			if (numAttackableSoldiers >= 1) {
				// Decide whether to attack, retreat, or reposition
				if (numAttackableSoldiers >= 2) { // We're getting double-teamed!!
					// retreat unless one of our attackers is also getting double-teamed just as bad
					boolean anEnemyIsDoubleTeamed = false;
					for (int i = attackableEnemies.length; i-- > 0;) {
						if (attackableEnemies[i].type != RobotType.SOLDIER) continue; // don't care if non-soldier enemies are double-teamed
						MapLocation enemyLoc = attackableEnemies[i].location;
						// TODO: count of allies should really only count soldiers
						anEnemyIsDoubleTeamed |= numOtherAlliedSoldiersInAttackRange(enemyLoc) >= 1;
						if (anEnemyIsDoubleTeamed) break;
					}
					if (anEnemyIsDoubleTeamed) { // Fight!
						Debug.indicate("micro", 1, "double-teamed, but so is an enemy: fighting");
						attackANonConstructingSoldier();
						return;
					} else { // no enemy is double-teamed. Retreat!
						Debug.indicate("micro", 1, "double-teamed: retreating");
						retreatOrFight();
						return;
					}
				} else { // We're not getting double-teamed. We're within attack range of exactly one soldier
							// Fight on if we are winning the 1v1 or if the other guy is double-teamed. Otherwise retreat
							// But if we are in aggressive mode, never retreat!
							// First find the single enemy
					RobotInfo enemySoldier = Util.findANonConstructingSoldier(attackableEnemies);
					// TODO: count of allies should really only count soldiers
					if (stance == MicroStance.AGGRESSIVE || enemySoldier.health <= rc.getHealth() || enemySoldier.isConstructing
							|| numOtherAlliedSoldiersInAttackRange(enemySoldier.location) >= 1) {
						// Kill him!
						Debug.indicate("micro", 1, "winning vs single enemy: fighting");
						attackAndRecord(enemySoldier);
						return;
					} else {
						// retreat!
						Debug.indicate("micro", 1, "losing vs single enemy: retreating (numOtherAllies in range of " + enemySoldier.location.toString()
								+ ") is " + numOtherAlliedSoldiersInAttackRange(enemySoldier.location));
						retreatOrFight();
						return;
					}
				}
			} else { // can't attack a soldier
				boolean canSeeSoldier = Util.countNonConstructingSoldiers(visibleEnemies) > 0;
				if (canSeeSoldier) {
					// handle the case when we can attack a building, but can see a soldier.
					// Need to decide whether to move to attack a soldier or attack the building
					// For the moment, let's just attack a building.
					// TODO: add some code that makes us move to support allies when appropriate
					MapLocation closestSoldier = Util.closestNonConstructingSoldier(visibleEnemies, here);

					int numAlliesFightingClosestEnemySoldier = numOtherAlliedSoldiersInAttackRange(closestSoldier);
					if (numAlliesFightingClosestEnemySoldier > 0) {
						// We deliberately count allied buildings below so that we are more aggressively about engaging enemies who attack our buildings
						int maxEnemyExposure = numAlliesFightingClosestEnemySoldier;
						if (stance == MicroStance.AGGRESSIVE && maxEnemyExposure > 0) maxEnemyExposure++;
						tryMoveTowardLocationWithMaxEnemyExposure(closestSoldier, maxEnemyExposure, Nav.Sneak.NO);
						if (!rc.isActive()) return;
					}
					Debug.indicate("micro", 1, "can see a soldier, but preferring to attack a building");
					attackAHelplessEnemy();
					return;
				} else {
					// Can't see any soldiers, but can attack a building. Do it
					Debug.indicate("micro", 1, "can't see a soldier; attacking a building");
					attackAHelplessEnemy();
					return;
				}
			}
		} else { // No attackable enemies! But there is at least one visible enemy
			int numVisibleEnemySoldiers = Util.countNonConstructingSoldiers(visibleEnemies);
			if (numVisibleEnemySoldiers > 0) {
				// No enemy is currently in attack range. We can see at least one soldier.
				// There are a few things we have to think about doing here.
				// * If no combat is going on, then what happens depends on our current plans:
				// - If we are moving to attack some point, then we should wait until we have a reasonable number of
				// allies nearby and then attack. Ideally we should do a coordinated attack so that individual
				// robots don't plunge in on their own and die.
				// - If we had a non-attack destination we were going to, we should continue on to that. Doing
				// that safely will require the Nav system to avoid squares attacked by enemies.
				// - If we are defending some point then maybe we can continue what we were doing, as long as we don't
				// walk into the enemy, or maybe a good reaction would be to bunch up with nearby allies
				// * If combat is going on, then we need to decide whether to join in, and how to join in.
				// Something that we particularly want to avoid is jumping in to help an ally just as that ally decides to retreat
				//
				// For now we are just going to stand off and move towards the nearest enemy as long as we don't engage.
				// TODO: we can safely engage single robots with large enough actionDelay if we do it orthogonally
				MapLocation closestSoldier = Util.closestNonConstructingSoldier(visibleEnemies, here);
				// We deliberately count allied buildings below so that we are more aggressively about engaging enemies who attack our buildings
				int maxEnemyExposure = numOtherAlliedSoldiersAndBuildingsInAttackRange(closestSoldier);
				if (stance == MicroStance.AGGRESSIVE && maxEnemyExposure > 0) maxEnemyExposure++;
				tryMoveTowardLocationWithMaxEnemyExposure(closestSoldier, maxEnemyExposure, Nav.Sneak.NO);
				return;
			} else { // Can't see a soldier, only buildings.
						// Make sure we aren't just seeing the HQ
				boolean canSeeBuilding = visibleEnemies.length > 2 || visibleEnemies[0].type != RobotType.HQ;
				if (canSeeBuilding) { // can see a non-HQ building
					// We are not really fighting. Move toward the building so we eventually kill it
					MapLocation closestBuilding = Util.closestNonHQ(visibleEnemies, rc);
					Direction toBuilding = here.directionTo(closestBuilding);
					int[] offsets = new int[] { 0, 1, -1, 2, -2, 3, -3, 4 };
					for (int i = 0; i < offsets.length; i++) {
						Direction tryDir = Direction.values()[(toBuilding.ordinal() + offsets[i] + 8) % 8];
						if (rc.canMove(tryDir) && !isInTheirHQAttackRange(here.add(tryDir))) {
							Debug.indicate("micro", 1, "moving towards visible building");
							rc.move(tryDir);
							return;
						}
					}
					Debug.indicate("micro", 1, "ignoring visible building because somehow we can't move");
					return;
				} else { // can only see the enemy HQ.
							// we are not really fighting. Let's move away from it so we can say we did something
					Debug.indicate("micro", 1, "can only see enemy HQ");
					Direction away = theirHQ.directionTo(here);
					if (rc.canMove(away)) rc.move(away);
					return;
				}
			}
		}
	}

	private static boolean tryMoveToEngageUndefendedHelplessEnemy() throws GameActionException {
		visibleEnemyLoop: for (int i = visibleEnemies.length; i-- > 0;) {
			RobotInfo enemy = visibleEnemies[i];
			if (Util.isHelpless(enemy)) {
				MapLocation enemyLocation = enemy.location;
				// Debug.indicate("micro", 1, "see helpless enemy at " + enemyLocation.toString());
				Direction toEnemyDir = here.directionTo(enemyLocation);
				if (!rc.canMove(toEnemyDir)) continue visibleEnemyLoop;
				MapLocation pathLoc = here;
				while (pathLoc.distanceSquaredTo(enemyLocation) > RobotType.SOLDIER.attackRadiusMaxSquared) {
					pathLoc = pathLoc.add(pathLoc.directionTo(enemyLocation));
					if (rc.senseTerrainTile(pathLoc) == TerrainTile.VOID) continue visibleEnemyLoop;
					if (isInTheirHQAttackRange(pathLoc)) continue visibleEnemyLoop;
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

	private static boolean tryMoveTowardLocationWithMaxEnemyExposure(MapLocation dest, int maxEnemyExposure, Nav.Sneak sneak) throws GameActionException {
		int[] numEnemiesAttackingDirs = countNumEnemiesAttackingMoveDirs();

		Direction toEnemy = here.directionTo(dest);
		Direction[] tryDirs = new Direction[] { toEnemy, toEnemy.rotateLeft(), toEnemy.rotateRight() };
		for (int i = 0; i < tryDirs.length; i++) {
			Direction tryDir = tryDirs[i];
			if (!rc.canMove(tryDir)) continue;
			if (numEnemiesAttackingDirs[tryDir.ordinal()] > maxEnemyExposure) continue;
			if (isInTheirHQAttackRange(here.add(tryDir))) continue;
			// Debug.indicate("micro", 1, String.format("moving toward %s with max enemy exposure %d (actual exposure %d)", dest.toString(), maxEnemyExposure,
			// numEnemiesAttackingDirs[tryDir.ordinal()]));
			if (sneak == Nav.Sneak.YES) rc.move(tryDir);
			else rc.sneak(tryDir);
			return true;
		}
		// Debug.indicate("micro", 1, String.format("can't move toward %s with max enemy exposure %d", dest.toString(), maxEnemyExposure));
		return false;
	}

	// Costs ~1k bytecodes :(
	private static boolean tryToKillCows() throws GameActionException {
		MapLocation bestTarget = null;
		double mostCows = 300;
		MapLocation[] shootLocs = MapLocation.getAllMapLocationsWithinRadiusSq(here, RobotType.SOLDIER.attackRadiusMaxSquared);
		for (int i = shootLocs.length; i-- > 0;) { // 4 per loop
			double cows = rc.senseCowsAtLocation(shootLocs[i]); // 14
			if (cows > mostCows) { // 4
				MapLocation target = shootLocs[i];
				if (target.equals(here)) continue;
				if (cows > 3000 || rc.senseNearbyGameObjects(Robot.class, target, 0, us).length == 0) {
					mostCows = cows;
					bestTarget = target;
				}
			}
		}
		if (bestTarget != null) {
			rc.attackSquare(bestTarget);
			return true;
		} else {
			return false;
		}
	}

	private static int numOtherAlliedSoldiersAndBuildingsInAttackRange(MapLocation loc) {
		return rc.senseNearbyGameObjects(Robot.class, loc, RobotType.SOLDIER.attackRadiusMaxSquared, us).length;
	}

	private static int numOtherAlliedSoldiersInAttackRange(MapLocation loc) throws GameActionException {
		return numOtherAlliedSoldiersInRange(loc, RobotType.SOLDIER.attackRadiusMaxSquared);
	}

	private static int numOtherAlliedSoldiersInRange(MapLocation loc, int rangeSq) throws GameActionException {
		int numAlliedSoldiers = 0;
		Robot[] allies = rc.senseNearbyGameObjects(Robot.class, loc, rangeSq, us);
		for (int i = allies.length; i-- > 0;) {
			if (rc.senseRobotInfo(allies[i]).type == RobotType.SOLDIER) numAlliedSoldiers++;
		}
		return numAlliedSoldiers;
	}

	// Assumes attackableEnemies contains a non-constructing soldier
	private static void attackANonConstructingSoldier() throws GameActionException {
		RobotInfo target = chooseNonConstructingSoldierAttackTarget(attackableEnemies);
		attackAndRecord(target);
	}

	private static RobotInfo chooseNonConstructingSoldierAttackTarget(RobotInfo[] enemies) throws GameActionException {
		RobotInfo ret = null;
		double bestTurnsToKill = 999;
		double bestActionDelay = 999;
		for (int i = enemies.length; i-- > 0;) {
			RobotInfo info = enemies[i];
			if (info.type != RobotType.SOLDIER || info.isConstructing) continue;
			int numNearbyAllies = 1 + numOtherAlliedSoldiersInAttackRange(info.location);
			double turnsToKill = info.health / numNearbyAllies;
			if (turnsToKill < bestTurnsToKill) {
				bestTurnsToKill = turnsToKill;
				bestActionDelay = info.actionDelay;
				ret = info;
			} else if (turnsToKill == bestTurnsToKill) {
				double actionDelay = info.actionDelay;
				if (actionDelay < bestActionDelay) {
					bestActionDelay = actionDelay;
					ret = info;
				}
			}
		}
		// Debug.indicate("micro", 2, "chooseNonConstructingSoldierAttackTarget: target = " + ret.location.toString() + ", bestTurnsToKill = " +
		// bestTurnsToKill);
		return ret;
	}

	// Assumes attackableEnemies is non-empty and only contains helpless enemies
	private static void attackAHelplessEnemy() throws GameActionException {
		RobotInfo target = chooseHelplessAttackTarget();
		attackAndRecord(target);
	}

	// If we can only attack buildings or constructing soldiers, this function decides which one to attack.
	// It assumes that the enemies list does not contain any non-constructing soldiers or HQs!
	private static RobotInfo chooseHelplessAttackTarget() throws GameActionException {
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

	private static void retreatOrFight() throws GameActionException {
		// If all our opponents have really high action delay, we can fire a last shot
		// and still be able to move before they can return fire. This would most probably
		// happen if an enemy engaged us after several diagonal moves. This could turn
		// a losing 1v1 into a winning one! Also, if we can one-hit an enemy we should
		// do so instead of retreating even if we take hits to do so
		boolean canOneHitEnemy = false;
		boolean enemyCanShootAtUs = false;
		for (int i = attackableEnemies.length; i-- > 0;) {
			RobotInfo enemy = attackableEnemies[i];
			if (enemy.health <= RobotType.SOLDIER.attackPower) {
				canOneHitEnemy = true;
				break;
			}
			if (enemy.actionDelay < 3.0 && !enemy.isConstructing) {
				enemyCanShootAtUs = true;
			}
		}

		if (canOneHitEnemy || !enemyCanShootAtUs) {
			// Debug.indicate("micro", 2, "parthian shot (canOneHitEnemy = " + canOneHitEnemy + ", enemyCanShootAtUs = " + enemyCanShootAtUs + ")");
			attackANonConstructingSoldier();
			return;
		}

		Direction dir = chooseRetreatDirection();
		if (dir == null) { // Can't retreat! Fight!
			// Debug.indicate("micro", 2, "couldn't retreat; fighting instead");
			attackANonConstructingSoldier();
		} else { // Can retreat. Do it!
			// Debug.indicate("micro", 2, "retreating successfully");
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

	private static int[] countNumEnemiesAttackingMoveDirs() {
		if (cachedNumEnemiesAttackingMoveDirs != null) return cachedNumEnemiesAttackingMoveDirs;

		Debug.debug_bytecodes("before countAttackingDirs");
		int[] numEnemiesAttackingDir = new int[8];
		for (int i = visibleEnemies.length; i-- > 0;) {
			RobotInfo info = visibleEnemies[i];
			if (info.type == RobotType.SOLDIER && !info.isConstructing) {
				MapLocation enemyLoc = info.location;
				int[] attackedDirs = attackNotes[5 + enemyLoc.x - here.x][5 + enemyLoc.y - here.y];
				for (int j = attackedDirs.length; j-- > 0;) {
					numEnemiesAttackingDir[attackedDirs[j]]++;
				}
			}
		}
		Debug.debug_bytecodes("after countAttackingDirs");
		cachedNumEnemiesAttackingMoveDirs = numEnemiesAttackingDir;
		return numEnemiesAttackingDir;
	}

	private static Direction chooseRetreatDirection() throws GameActionException {
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
			if (isInTheirHQAttackRange(tryLoc)) continue;

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
	private static boolean mercyKillPastrs() throws GameActionException {
		MapLocation[] ourPastrs = rc.sensePastrLocations(us);
		for (int i = ourPastrs.length; i-- > 0;) {
			MapLocation pastrLoc = ourPastrs[i];
			if (here.distanceSquaredTo(ourPastrs[i]) <= RobotType.SOLDIER.attackRadiusMaxSquared) {
				if (rc.senseRobotInfo(rc.senseNearbyGameObjects(Robot.class, pastrLoc, 0, us)[0]).health <= RobotType.SOLDIER.attackPower) {
					rc.attackSquare(pastrLoc);
					return true;
				}
			}
		}
		return false;
	}

	private static boolean weAreNearEnemyPastr() {
		MapLocation[] enemyPastrs = rc.sensePastrLocations(them);
		int smallestEnemyDistSq = 999999;
		for (int i = enemyPastrs.length; i-- > 0;) {
			int distSq = here.distanceSquaredTo(enemyPastrs[i]);
			if (distSq < smallestEnemyDistSq) smallestEnemyDistSq = distSq;
		}
		if (smallestEnemyDistSq > 70) return false;
		MapLocation[] ourPastrs = rc.sensePastrLocations(us);
		int smallestAllyDistSq = 999999;
		for (int i = ourPastrs.length; i-- > 0;) {
			int distSq = here.distanceSquaredTo(ourPastrs[i]);
			if (distSq < smallestAllyDistSq) smallestAllyDistSq = distSq;
		}
		return smallestEnemyDistSq < smallestAllyDistSq;
	}

	private static void attackAndRecord(RobotInfo enemyInfo) throws GameActionException {
		if (enemyInfo == null) return; // should never happen, but just to be sure
		rc.attackSquare(enemyInfo.location);
		if (enemyInfo.health <= RobotType.SOLDIER.attackPower) MessageBoard.ROUND_KILL_COUNT.incrementInt();
	}

}
