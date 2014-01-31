package zephyr;

import battlecode.common.*;

public class BotSoldier extends Bot {
	public static void loop(RobotController theRC) throws Exception {
		init(theRC);
		rc.yield(); // initialization takes a lot of bytecodes
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
		// Debug.init(theRC, "heal");
		Nav.init(theRC);

		spawnOrder = MessageBoard.SPAWN_COUNT.readInt();

		tryReceivePastrLocations();
		tryClaimBuildAssignment();
		if (towerBuildAssignmentIndex == -1 && pastrBuildAssignmentIndex == -1) {
			tryClaimSuppressorAssignment();
		}
	}

	private enum MicroStance {
		SAFE, // Retreat a lot, play it safe. Appropriate when harrassing or gathering.
		DEFENSIVE, // Play it somewhat safe, but don't retreat too much because we have something to protect. Appropriate for defending
		AGGRESSIVE // Be somewhat reckless when many allies are nearby. Appropriate for attacking.
	}

	static MicroStance stance;

	static MapLocation here;
	static double health;
	static RobotInfo[] visibleEnemies; // enemies within vision radius (35)
	static RobotInfo[] attackableEnemies; // enemies within attack radius(10)
	static int numNonConstructingSoldiersAttackingUs;
	static int numVisibleNonConstructingEnemySoldiers;
	static int[] cachedNumEnemiesAttackingMoveDirs; // computed in doObligatoryMicro
	static boolean tryingSelfDestruct = false;
	static boolean inHealingState = false;
	static boolean needToClearOutRallyBeforeDefending = false;

	static int spawnOrder;

	static int numPastrLocations = 0;
	static MapLocation[] bestPastrLocations = new MapLocation[BotHQ.MAX_PASTR_LOCATIONS];
	static int towerBuildAssignmentIndex = -1;
	static int pastrBuildAssignmentIndex = -1;
	static int suppressorBuildAssignmentIndex = -1;
	static boolean isFirstTurn = true;

	private static void turn() throws GameActionException {
		here = rc.getLocation();
		health = rc.getHealth();
		if (!rc.isActive()) {
			// can self-destruct even with large actiondelay:
			if (tryingSelfDestruct) {
				updateEnemyData();
				trySelfDestruct();
			}
			return;
		}

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

		if (tryingSelfDestruct) {
			if (trySelfDestruct()) return;
		}

		MapLocation rallyLoc = MessageBoard.RALLY_LOC.readMapLocation();
		BotHQ.RallyGoal rallyGoal = MessageBoard.RALLY_GOAL.readRallyGoal();

		// TODO: clean up this hack
		if (Strategy.active == Strategy.SCATTER_HALF || Strategy.active == Strategy.SCATTER_SUPPRESSOR_HALF) {
			if ((spawnOrder % 2) == 0) {
				if (rallyGoal == BotHQ.RallyGoal.HARRASS) {
					if (numPastrLocations == 1) {
						MapLocation[] ourPastrs = rc.sensePastrLocations(us);
						if (ourPastrs.length == 1) {
							rallyLoc = ourPastrs[0];
							rallyGoal = BotHQ.RallyGoal.DEFEND;
						}
					}
				}
			}
		}

		manageHealingState();

		stance = chooseMicroStance(rallyLoc, rallyGoal);

		if (visibleEnemies.length > 0 && rc.isActive()) {
			if (doObligatoryMicro()) {
				return;
			}
		}

		// if(inHealingState) {
		// Nav.goTo(ourHQ, Nav.Sneak.NO, Nav.Engage.NO, countNumEnemiesAttackingMoveDirs());
		// return;
		// }

		if (tryBuildSomething()) {
			return;
		}

		// Check if someone else has taken our job from us:
		if (!isFirstTurn) {
			manageBuildAssignment();
		}
		isFirstTurn = false;

		Nav.Engage navEngage = stance == MicroStance.AGGRESSIVE ? Nav.Engage.YES : Nav.Engage.NO;

		int destIndex = towerBuildAssignmentIndex;
		if (destIndex == -1) destIndex = pastrBuildAssignmentIndex;
		if (destIndex != -1) {
			Nav.goTo(bestPastrLocations[destIndex], Nav.Sneak.NO, navEngage, countNumEnemiesAttackingMoveDirs());
			return;
		}

		if (suppressorBuildAssignmentIndex != -1) {
			MapLocation target = MessageBoard.SUPPRESSOR_TARGET_LOCATIONS.readFromMapLocationList(suppressorBuildAssignmentIndex);
			Nav.goTo(target, Nav.Sneak.NO, Nav.Engage.NO, countNumEnemiesAttackingMoveDirs());
			return;
		}

		if (rallyLoc != null) {
			if (weAreNearEnemyPastr()) {
				if (tryToKillCows()) {
					return;
				}
			}
			if (visibleEnemies.length > 0) {
				// if (doVoluntaryMicro(rallyLoc, rallyGoal, sneak)) {
				if (doVoluntaryMicro(rallyLoc, rallyGoal, Nav.Sneak.NO)) {
					return;
				}
			}
			Nav.Sneak sneak = Nav.Sneak.NO;
			for (int i = 0; i < numPastrLocations; i++) {
				if (here.distanceSquaredTo(bestPastrLocations[i]) <= 49) {
					sneak = Nav.Sneak.YES;
					break;
				}
			}
			Nav.goTo(rallyLoc, sneak, navEngage, countNumEnemiesAttackingMoveDirs());
		}
	}

	// returns false if turn() should return, otherwise true
	private static boolean tryReceivePastrLocations() throws GameActionException {
		numPastrLocations = MessageBoard.NUM_PASTR_LOCATIONS.readInt();
		if (numPastrLocations == 0) {
			if (Strategy.active == Strategy.PROXY || Strategy.active == Strategy.PROXY_ATTACK || Strategy.active == Strategy.RUSH) return true;
			else return false;
		}

		for (int i = 0; i < numPastrLocations; i++) {
			bestPastrLocations[i] = MessageBoard.BEST_PASTR_LOCATIONS.readFromMapLocationList(i);
		}

		if (Strategy.active == Strategy.ONE_PASTR || Strategy.active == Strategy.ONE_PASTR_SUPPRESSOR || Strategy.active == Strategy.SCATTER
				|| Strategy.active == Strategy.SCATTER_HALF || Strategy.active == Strategy.SCATTER_SUPPRESSOR
				|| Strategy.active == Strategy.SCATTER_SUPPRESSOR_HALF) {
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
				return;
			}
		}
		for (int i = 0; i < numPastrLocations; i++) {
			if (MessageBoard.PASTR_BUILDER_ROBOT_IDS.checkIfAssignmentUnowned(i)) {
				MessageBoard.PASTR_BUILDER_ROBOT_IDS.claimAssignment(i);
				pastrBuildAssignmentIndex = i;
				return;
			}
		}
	}

	private static void tryClaimSuppressorAssignment() throws GameActionException {
		int numSuppressors = MessageBoard.NUM_SUPPRESSORS.readInt();
		for (int i = 0; i < numSuppressors; i++) {
			if (MessageBoard.SUPPRESSOR_BUILDER_ROBOT_IDS.checkIfAssignmentUnowned(i)) {
				MessageBoard.SUPPRESSOR_BUILDER_ROBOT_IDS.claimAssignment(i);
				suppressorBuildAssignmentIndex = i;
			}
		}
	}

	private static void manageBuildAssignment() throws GameActionException {
		if (towerBuildAssignmentIndex != -1) {
			if (!MessageBoard.TOWER_BUILDER_ROBOT_IDS.checkIfIOwnAssignment(towerBuildAssignmentIndex)) {
				// check if we can reassign ourselves to the corresponding pastr
				if (MessageBoard.PASTR_BUILDER_ROBOT_IDS.checkIfAssignmentUnowned(towerBuildAssignmentIndex)) {
					MessageBoard.PASTR_BUILDER_ROBOT_IDS.claimAssignment(towerBuildAssignmentIndex);
					pastrBuildAssignmentIndex = towerBuildAssignmentIndex;
				}
				towerBuildAssignmentIndex = -1;
			}
		} else if (pastrBuildAssignmentIndex != -1) {
			if (!MessageBoard.PASTR_BUILDER_ROBOT_IDS.checkIfIOwnAssignment(pastrBuildAssignmentIndex)) {
				// check if we can reassign ourselves to the corresponding tower
				if (MessageBoard.TOWER_BUILDER_ROBOT_IDS.checkIfAssignmentUnowned(pastrBuildAssignmentIndex)) {
					MessageBoard.TOWER_BUILDER_ROBOT_IDS.claimAssignment(pastrBuildAssignmentIndex);
					towerBuildAssignmentIndex = pastrBuildAssignmentIndex;
				}
				pastrBuildAssignmentIndex = -1;
			}
		} else if (suppressorBuildAssignmentIndex != -1) {
			if (!MessageBoard.SUPPRESSOR_BUILDER_ROBOT_IDS.checkIfIOwnAssignment(suppressorBuildAssignmentIndex)) suppressorBuildAssignmentIndex = -1;
		}
	}

	private static boolean areaIsSecureEnoughToBuild() throws GameActionException {
		int numDefenders = numOtherNonConstructingAlliedSoldiersInRange(here, RobotType.SOLDIER.sensorRadiusSquared);
		int numAttackers = visibleEnemies.length;
		if (numAttackers > 0 && numDefenders <= numAttackers + 3) return false;
		return true;
	}

	private static boolean tryBuildSomething() throws GameActionException {
		for (int i = 0; i < numPastrLocations; i++) {
			MapLocation pastrLoc = bestPastrLocations[i];
			if (here.equals(pastrLoc)) {
				// claim the pastr build job if necessary
				if (!MessageBoard.PASTR_BUILDER_ROBOT_IDS.checkIfIOwnAssignment(i)) {
					MessageBoard.PASTR_BUILDER_ROBOT_IDS.claimAssignment(i);
				}
				// if we were going to build a tower, relinquish our claim to that assignment
				if (towerBuildAssignmentIndex != -1) {
					MessageBoard.TOWER_BUILDER_ROBOT_IDS.clearAssignment(towerBuildAssignmentIndex);
					towerBuildAssignmentIndex = -1;
				}
				if (MessageBoard.BUILD_PASTRS_FAST.readBoolean() || Util.containsNoiseTower(rc.senseNearbyGameObjects(Robot.class, pastrLoc, 2, us), rc)) {
					if (!areaIsSecureEnoughToBuild()) return false;
					rc.construct(RobotType.PASTR);
				}
				return true;
			}

			if (here.isAdjacentTo(pastrLoc)) {
				if (!Util.containsConstructingRobotOrNoiseTowerExceptAtLocation(rc.senseNearbyGameObjects(Robot.class, pastrLoc, 2, us), pastrLoc, rc)) {
					if (!areaIsSecureEnoughToBuild()) return false;

					Direction dirToPastr = here.directionTo(pastrLoc);
					if (buildingHereBlocksAccess(dirToPastr) && rc.canMove(dirToPastr)) {
						// There is an embarrassing failure mode where building the noise tower blocks access to the pastr location,
						// and the pastr builder is not smart enough to route around the noise tower. We can try to ameliorate this by
						// checking whether building here would block access to the pastr. If it would, and if the pastr builder is not
						// in place yet, then we should move to the pastr location and become the pastr builder.
						rc.move(dirToPastr);
					} else {
						// claim the tower build job if necessary
						if (!MessageBoard.TOWER_BUILDER_ROBOT_IDS.checkIfIOwnAssignment(i)) {
							MessageBoard.TOWER_BUILDER_ROBOT_IDS.claimAssignment(i);
						}
						// if we were going to build a pastr, relinquish our claim to that assignment
						if (pastrBuildAssignmentIndex != -1) {
							MessageBoard.PASTR_BUILDER_ROBOT_IDS.clearAssignment(pastrBuildAssignmentIndex);
							pastrBuildAssignmentIndex = -1;
						}
						constructNoiseTower(pastrLoc);
						return true;
					}
				}
			}
		}

		// allowed to build a suppressor if we are assigned to or if we have no other assignments and are in harrass mode
		boolean allowedToBuildSuppressor = suppressorBuildAssignmentIndex != -1;
		if (pastrBuildAssignmentIndex == -1 && towerBuildAssignmentIndex == -1 && MessageBoard.RALLY_GOAL.readRallyGoal() == BotHQ.RallyGoal.HARRASS) {
			allowedToBuildSuppressor = true;
		}

		if (allowedToBuildSuppressor) {
			int numSuppressors = MessageBoard.NUM_SUPPRESSORS.readInt();
			for (int i = 0; i < numSuppressors; i++) {
				if (!MessageBoard.SUPPRESSOR_JOBS_FINISHED.readFromBooleanList(i)) {
					MapLocation suppressorTarget = MessageBoard.SUPPRESSOR_TARGET_LOCATIONS.readFromMapLocationList(i);
					int distSq = here.distanceSquaredTo(suppressorTarget);
					if (distSq <= RobotType.NOISETOWER.attackRadiusMaxSquared && distSq >= 10) {
						MessageBoard.SUPPRESSOR_BUILDER_ROBOT_IDS.claimAssignment(i);
						MessageBoard.SUPPRESSOR_JOBS_FINISHED.writeToBooleanList(i, true);
						if (!areaIsSecureEnoughToBuild()) return false;
						rc.construct(RobotType.NOISETOWER);
						return true;
					}
				}
			}
		}

		return false;
	}

	// a bad hack for a stupid problem
	private static boolean buildingHereBlocksAccess(Direction dir) {
		if (dir.isDiagonal()) {
			boolean voidL1 = !Util.passable(rc.senseTerrainTile(here.add(dir.rotateLeft())));
			boolean voidL3 = !Util.passable(rc.senseTerrainTile(here.add(dir.rotateLeft().rotateLeft().rotateLeft())));
			boolean voidR1 = !Util.passable(rc.senseTerrainTile(here.add(dir.rotateRight())));
			boolean voidR3 = !Util.passable(rc.senseTerrainTile(here.add(dir.rotateRight().rotateRight().rotateRight())));
			return (voidL1 && voidR1) || (voidL1 && voidR3) || (voidL3 && voidR1) || (voidL3 && voidR3);
		} else {
			boolean voidL2 = !Util.passable(rc.senseTerrainTile(here.add(dir.rotateLeft().rotateLeft())));
			boolean voidR2 = !Util.passable(rc.senseTerrainTile(here.add(dir.rotateRight().rotateRight())));
			return (voidL2 && voidR2);
		}
	}

	private static void constructNoiseTower(MapLocation pastrLoc) throws GameActionException {
		rc.construct(RobotType.NOISETOWER);
		// HerdPattern.computeAndPublish(here, pastrLoc, rc);
	}

	private static void updateEnemyData() throws GameActionException {
		Robot[] visibleEnemyRobots = rc.senseNearbyGameObjects(Robot.class, RobotType.SOLDIER.sensorRadiusSquared, them);

		visibleEnemies = new RobotInfo[visibleEnemyRobots.length];
		numVisibleNonConstructingEnemySoldiers = 0;
		for (int i = visibleEnemyRobots.length; i-- > 0;) {
			visibleEnemies[i] = rc.senseRobotInfo(visibleEnemyRobots[i]);
			if (visibleEnemies[i].type == RobotType.SOLDIER && !visibleEnemies[i].isConstructing) numVisibleNonConstructingEnemySoldiers++;
		}

		numNonConstructingSoldiersAttackingUs = 0;
		if (visibleEnemies.length > 0) {
			Robot[] attackableEnemyRobots = rc.senseNearbyGameObjects(Robot.class, RobotType.SOLDIER.attackRadiusMaxSquared, them);
			attackableEnemies = new RobotInfo[attackableEnemyRobots.length];
			for (int i = attackableEnemies.length; i-- > 0;) {
				RobotInfo info = rc.senseRobotInfo(attackableEnemyRobots[i]);
				attackableEnemies[i] = info;
				if (info.type == RobotType.SOLDIER && !info.isConstructing) {
					numNonConstructingSoldiersAttackingUs++;
				}
			}
		} else {
			attackableEnemies = new RobotInfo[0];
		}

		// clear the cached value of this array at the beginning of each turn:
		cachedNumEnemiesAttackingMoveDirs = null;
	}

	private static void manageHealingState() {
		if (!tryingSelfDestruct) {
			if (health < 20) {
				inHealingState = true;
				// Debug.indicate("heal", 0, "<20 health: going to healing state");
			} else if (attackableEnemies.length > 0) {
				// go into healing mode if we could get killed in the next turn
				int numAttackers = 0;
				for (int i = attackableEnemies.length; i-- > 0;) {
					RobotInfo enemy = attackableEnemies[i];
					if (enemy.actionDelay < 3 && enemy.type == RobotType.SOLDIER) numAttackers++;
				}
				if ((numAttackers + 1) * RobotType.SOLDIER.attackPower >= health) {
					// Debug.indicate("heal", 0, "" + numAttackers + " attackers: going to healing state");
					inHealingState = true;
				} else {
					// Debug.indicate("heal", 0, "not going to healing state: health = " + health + ", numAttackers = " + numAttackers);
				}
			}
		}
		if (health > 80) {
			inHealingState = false;
			// Debug.indicate("heal", 0, "leaving healing state");
		}
	}

	private static boolean trySelfDestruct() throws GameActionException {
		double maxDamage = GameConstants.SELF_DESTRUCT_BASE_DAMAGE + GameConstants.SELF_DESTRUCT_DAMAGE_FACTOR * health;
		double damageDealt = 0;
		for (RobotInfo info : attackableEnemies) {
			if (info.location.isAdjacentTo(here) && info.type == RobotType.SOLDIER && !info.isConstructing) {
				damageDealt += Math.min(info.health, maxDamage);
			}
		}
		damageDealt -= maxDamage * rc.senseNearbyGameObjects(Robot.class, here, 2, us).length;
		if (damageDealt > health * 1.5 || (damageDealt > health && health < 1.5 * RobotType.SOLDIER.attackPower * numNonConstructingSoldiersAttackingUs)) {
			MessageBoard.SELF_DESTRUCT_LOCKOUT_ROUND.writeInt(0); // OK to others to self-destruct now
			rc.selfDestruct();
			return true;
		}
		return false;
	}

	private static MicroStance chooseMicroStance(MapLocation rallyLoc, BotHQ.RallyGoal rallyGoal) {
		switch (rallyGoal) {
			case DEFEND:
				needToClearOutRallyBeforeDefending = false;
				if (here.distanceSquaredTo(rallyLoc) > 64) {
					return MicroStance.AGGRESSIVE; // punch through to our pastr
				}
				// We are close to the rally point. But if there is still an enemy between us and the rally point, we have to be aggressive
				MapLocation ahead = here.add(here.directionTo(rallyLoc), 2);
				if (rc.senseNearbyGameObjects(Robot.class, ahead, RobotType.SOLDIER.attackRadiusMaxSquared, them).length > 0) {
					needToClearOutRallyBeforeDefending = true;
					return MicroStance.AGGRESSIVE;
				}
				// Otherwise, we have made it to the pastr and can be defensive
				return MicroStance.DEFENSIVE; // defend the pastr

			case DESTROY:
				return MicroStance.AGGRESSIVE; // let nothing stand in our way

			case GATHER:
				return MicroStance.SAFE; // Don't waste units

			case HARRASS:
				if (here.distanceSquaredTo(rallyLoc) > 100) return MicroStance.AGGRESSIVE; // punch through to the target
				else return MicroStance.SAFE; // Don't lose units

			default:
				System.out.println("Unknown rally goal!!!");
				return MicroStance.DEFENSIVE;
		}
	}

	private static boolean doVoluntaryMicro(MapLocation rallyLoc, BotHQ.RallyGoal rallyGoal, Nav.Sneak sneak) throws GameActionException {
		switch (rallyGoal) {
			case GATHER:
			case DEFEND:
				if (needToClearOutRallyBeforeDefending) return false;
				if (MessageBoard.COLLAPSE_TO_PASTR_SIGNAL.readInt() >= Clock.getRoundNum() - 1) return false;
				if (here.distanceSquaredTo(rallyLoc) <= 36) {
					MapLocation closestEnemy = Util.closestNonHQ(visibleEnemies, rc);
					if (closestEnemy == null) return false;
					tryMoveTowardLocationWithNoEnemyExposureWithinRadius(closestEnemy, rallyLoc, 36, sneak);
					return true;
				}
				return false;

			case DESTROY:
				return false;

			case HARRASS:
				if (here.distanceSquaredTo(rallyLoc) <= 81) {
					if (tryMoveTowardLocationWithMaxEnemyExposure(rallyLoc, 0, sneak)) {
						Debug.indicate("micro", 0, "voluntary: moving safely toward rally in harrass mode");
					}
					return true;
				}
				return false;

			default: // shouldn't happen
				return false;
		}
	}

	// obligatory micro is attacks or movements that we have to do because either we or a nearby ally is in combat
	private static boolean doObligatoryMicro() throws GameActionException {
		// Decide whether to try to move in for a self-destruct
		if (!inHealingState) {
			if (Clock.getRoundNum() >= MessageBoard.SELF_DESTRUCT_LOCKOUT_ROUND.readInt()
					|| rc.getRobot().getID() == MessageBoard.SELF_DESTRUCT_LOCKOUT_ID.readInt()) {
				if (numNonConstructingSoldiersAttackingUs >= 1 && tryMoveForSelfDestruct()) {
					// Debug.indicate("micro", 0, "trying for one-move self-destruct");
					tryingSelfDestruct = true;
					return true;
				} else if (tryMoveForTwoMoveSelfDestruct()) {
					// Debug.indicate("micro", 0, "trying for two-move self-destruct");
					tryingSelfDestruct = true;
					return true;
				} else {
					// Debug.indicate("micro", 1, "not trying self-destruct");
					tryingSelfDestruct = false;
				}
			}
		}

		if (numNonConstructingSoldiersAttackingUs >= 1) {
			if (fleeSelfDestruct()) {
				// Debug.indicate("micro", 0, "fleeing self-destruct");
				return true;
			}
		}

		if (numNonConstructingSoldiersAttackingUs >= 1) { // we are in combat
			// If we are getting double-teamed worse than any of the enemies we are fighting, try to retreat
			int maxAlliesAttackingEnemy = 0;
			for (int i = attackableEnemies.length; i-- > 0;) {
				// int numAlliesAttackingEnemy = 1 + numOtherAlliedSoldiersInAttackRange(attackableEnemies[i].location);
				// we deliberately include buildings in this count to encourage our soldiers to defend buildings:
				int numAlliesAttackingEnemy = 1 + numOtherAlliedSoldiersAndBuildingsInAttackRange(attackableEnemies[i].location);
				if (numAlliesAttackingEnemy > maxAlliesAttackingEnemy) maxAlliesAttackingEnemy = numAlliesAttackingEnemy;
			}
			if (numNonConstructingSoldiersAttackingUs == 1) {
				if (maxAlliesAttackingEnemy == 1) {
					// we are in a 1v1. fight if we are winning, otherwise retreat
					RobotInfo singleEnemy = Util.findANonConstructingSoldier(attackableEnemies);
					boolean weAreWinning1v1 = health >= singleEnemy.health;
					if (weAreWinning1v1) {
						// Debug.indicate("micro", 0, "attacking in winning 1v1");
						attackAndRecord(singleEnemy);
					} else {
						if (!inHealingState && stance == MicroStance.AGGRESSIVE && guessIfFightIsWinning()) {
							// Debug.indicate("micro", 0, "remaining in losing 1v1 because we are aggressive and fight is winning");
							attackAndRecord(singleEnemy);
						} else {
							// Debug.indicate("micro", 0, "retreating from losing 1v1");
							retreatOrFight();
						}
					}
				} else {
					// we outnumber the lone enemy.
					RobotInfo singleEnemy = Util.findANonConstructingSoldier(attackableEnemies);
					if (inHealingState && health < singleEnemy.health) {
						// if we are trying to heal, don't stay in the fight if we have less health than the enemy
						retreatOrFight();
					} else {
						// otherwise kill the enemy
						// Debug.indicate("micro", 0, "attacking outnumbered lone enemy");
						attackAndRecord(singleEnemy);
					}
				}
			} else if (inHealingState || stance == MicroStance.SAFE
					|| (numNonConstructingSoldiersAttackingUs > maxAlliesAttackingEnemy && (stance != MicroStance.AGGRESSIVE || !guessIfFightIsWinning()))) {
				// Retreat if >= 2 enemies are attacking us and:
				// - we are trying to heal, or
				// - we are in a safe stance, or
				// - we are getting double-teamed worse than the enemy and in either a defensive stance or in a losing overall fight
				// Debug.indicate("micro", 0, "outnumbered: retreating");
				retreatOrFight();
			} else {
				// Several enemies can attack us, but we are also double teaming the enemy badly enough to keep fighting
				// Debug.indicate("micro", 0, "we have a good enough double-team not to retreat: attacking");
				attackANonConstructingSoldier();
			}
			return true;
		} else {// we are not in combat.
			// We are only obligated to do something here if a nearby ally is in combat and
			// we can attack a soldier who is attacking them. Then we have to decide whether to move to attack such a soldier
			// and if so which one. Actually, there is one other thing that is obligatory, which is to move to attack
			// helpless enemies (buildings and constructing soldiers) if it is safe to do so.
			if (!inHealingState && stance != MicroStance.SAFE) {
				MapLocation closestEnemySoldier = Util.closestNonConstructingSoldier(visibleEnemies, here);
				if (closestEnemySoldier != null) {
					// int numAlliesFighting = numOtherAlliedSoldiersInAttackRange(closestEnemySoldier);
					// we deliberately include buildings in this count to encourage our soldiers to defend buildings:
					int numAlliesFighting = numOtherNonSelfDestructingAlliedSoldiersAndBuildingsInAttackRange(closestEnemySoldier);
					if (numAlliesFighting > 0) {
						// Approach this enemy if doing so would expose us to at most numAlliesFighting enemies.
						// int maxEnemyExposure = numAlliesFighting + 1;
						int maxEnemyExposure = stance == MicroStance.AGGRESSIVE ? numAlliesFighting + 1 : Math.min(numAlliesFighting + 1, 3);
						if (tryMoveTowardLocationWithMaxEnemyExposure(closestEnemySoldier, maxEnemyExposure, Nav.Sneak.NO)) {
							// Debug.indicate("micro", 0, "moving to engage to assist ally");
							return true;
						}
					}
				}
			}

			// If we didn't have to go help an ally, or were unable to, check if there is something nearby we can shoot.
			// If so, shoot it. It must be a constructing solder or building because it's not a non-constructing soldier
			if (attackableEnemies.length > 0) {
				// Actually, we need to prevent the following situation: we can see a noise tower and a pastr, but are only in
				// range of the noise tower, so we attack the noise tower instead of moving to attack the pastr. It's important
				// to not waste any rounds killing pastrs. So check if we can see a pastr but not shoot a pastr
				if (tryMovetoEngageUndefendedPastr()) {
					// Debug.indicate("micro", 0, "moving to engage undefended pastr");
					return true;
				}

				// Debug.indicate("micro", 0, "attacking a helpless enemy");
				attackAHelplessEnemy();
				return true;
			}

			// We didn't have to help an ally or shoot a helpless enemy. One final possibility with an obligatory response:
			// if we can engage a helpless enemy and not get shot, do it
			if (tryMoveToEngageAnyUndefendedHelplessEnemy()) {
				// Debug.indicate("micro", 0, "moving to engage a helpless enemy");
				return true;
			}

			// If we are trying to heal, don't let anyone with more health than us get too close
			if (inHealingState) {
				// Debug.indicate("heal", 1, "micro: low health, fleeing");
				boolean tooClose = false;
				for (RobotInfo enemy : visibleEnemies) {
					if (enemy.type == RobotType.SOLDIER && !enemy.isConstructing && here.distanceSquaredTo(enemy.location) <= 35 && enemy.health > health) {
						tooClose = true;
						break;
					}
				}
				if (tooClose) {
					fleeWithLowHealth();
					return true;
				}
			}

			// If none of the above cases compelled us to action, there is no obligatory action
			return false;
		}
	}

	private static boolean guessIfFightIsWinning() {
		switch (numVisibleNonConstructingEnemySoldiers) {
			case 0:
				return true;
			case 1:
				return rc.senseNearbyGameObjects(Robot.class, here, 35, us).length >= 2;
			case 2:
				return rc.senseNearbyGameObjects(Robot.class, here, 35, us).length >= 4;
			case 3:
				return rc.senseNearbyGameObjects(Robot.class, here, 35, us).length >= 5;
			default:
				return rc.senseNearbyGameObjects(Robot.class, here, 35, us).length >= (int) (1.5 * numVisibleNonConstructingEnemySoldiers);
		}
	}

	private static boolean fleeSelfDestruct() throws GameActionException {
		RobotInfo adjacentEnemySoldier = null;
		for (RobotInfo info : attackableEnemies) {
			if (info.type != RobotType.SOLDIER || info.isConstructing) continue;
			if (info.location.isAdjacentTo(here) && info.health > RobotType.SOLDIER.attackPower) {
				adjacentEnemySoldier = info;
				break;
			}
		}
		if (adjacentEnemySoldier == null) return false;

		// Robot[] endangeredAllies = rc.senseNearbyGameObjects(Robot.class, adjacentEnemySoldier.location, 2, us);
		// double totalEndangeredAlliedHealth = health;
		// for (Robot ally : endangeredAllies) {
		// totalEndangeredAlliedHealth += rc.senseRobotInfo(ally).health;
		// }
		//
		// // If he risks more health than us, let him self-destruct, we don't care
		// if (totalEndangeredAlliedHealth <= adjacentEnemySoldier.health) return false;

		Direction bestFleeDir = null;
		int fewestAttackers = 99;
		int[] numEnemiesAttackingMoveDirs = countNumEnemiesAttackingMoveDirs();
		Direction away = adjacentEnemySoldier.location.directionTo(here);
		Direction[] fleeDirs;
		if (away.isDiagonal()) {
			fleeDirs = new Direction[] { away, away.rotateLeft(), away.rotateRight(), away.rotateLeft().rotateLeft(), away.rotateRight().rotateRight() };
		} else {
			Direction toBomber = here.directionTo(adjacentEnemySoldier.location);
			if (!rc.canMove(toBomber.rotateLeft().rotateLeft())) { // cheap approximate test for whether there is an ally to our left, looking toward enemy
				fleeDirs = new Direction[] { away.rotateLeft(), away, away.rotateRight() };
			} else if (!rc.canMove(toBomber.rotateRight().rotateRight())) { // cheap approximate test for whether there is an ally to our right, looking toward
																			// enemy
				fleeDirs = new Direction[] { away.rotateRight(), away, away.rotateLeft() };
			} else {
				fleeDirs = new Direction[] { away, away.rotateLeft(), away.rotateRight() };
			}
		}
		for (Direction fleeDir : fleeDirs) {
			if (!rc.canMove(fleeDir)) continue;
			if (here.add(fleeDir).isAdjacentTo(adjacentEnemySoldier.location)) continue;
			int numAttackers = numEnemiesAttackingMoveDirs[fleeDir.ordinal()];
			if (numAttackers < fewestAttackers) {
				fewestAttackers = numAttackers;
				bestFleeDir = fleeDir;
			}
		}
		if (bestFleeDir != null) {
			rc.move(bestFleeDir);
			return true;
		}
		return false;
	}

	// @formatter:off
	// This is a set of two-move paths that we could consider following and then self-destructing
	private static Direction[][] twoMoveSelfDestructPaths = {
		{Direction.NORTH, Direction.NORTH},
		{Direction.NORTH, Direction.NORTH_WEST},
		{Direction.NORTH, Direction.NORTH_EAST},
		{Direction.EAST, Direction.EAST},
		{Direction.EAST, Direction.NORTH_EAST},
		{Direction.EAST, Direction.SOUTH_EAST},
		{Direction.SOUTH, Direction.SOUTH},
		{Direction.SOUTH, Direction.SOUTH_EAST},
		{Direction.SOUTH, Direction.SOUTH_WEST},
		{Direction.WEST, Direction.WEST},
		{Direction.WEST, Direction.SOUTH_WEST},
		{Direction.WEST, Direction.NORTH_WEST},
		{Direction.NORTH_EAST, Direction.NORTH_EAST},
		{Direction.SOUTH_EAST, Direction.SOUTH_EAST},
		{Direction.SOUTH_WEST, Direction.SOUTH_WEST},
		{Direction.NORTH_WEST, Direction.NORTH_WEST}		
	};
	
	private static double[] twoMoveSelfDestructPathActionDelay = { 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2.8, 2.8, 2.8, 2.8 };
	
//	private static void tmp() {
//		MapLocation center = new MapLocation(0, 0);
//		for (int ex = -3; ex <= +3; ex++) {
//			System.out.print("{");
//			for (int ey = -3; ey <= +3; ey++) {
//				MapLocation enemyLoc = new MapLocation(ex, ey);
//				ArrayList<Integer> attacked = new ArrayList<Integer>();
//				for (int i = 0; i < twoMoveSelfDestructPaths.length; i++) {
//					MapLocation moveLoc = center.add(twoMoveSelfDestructPaths[i][0]).add(twoMoveSelfDestructPaths[i][1]);
//					if (moveLoc.isAdjacentTo(enemyLoc)) attacked.add(i);
//				}
//				System.out.print("{");
//				String numbers = "";
//				for (int i = 0; i < attacked.size(); i++) {
//					numbers += attacked.get(i);
//					if (i < attacked.size() - 1) numbers += ",";
//				}
//				System.out.print(numbers);
//				System.out.print("}");
//				if (ey < 3) System.out.print(",");
//				int spaces = 12 - numbers.length();
//				for (int i = 0; i < spaces; i++) {
//					System.out.print(" ");
//				}
//			}
//			System.out.println("}");
//		}
//	}
	//twoMoveSelfDestructTargetPaths[3+dx][3+dy] is a list of which twoMoveSelfDestructPaths would bring
	//us adjacent to a target at (here.x + dx, here.y + dy)
	private static int[][][] twoMoveSelfDestructTargetPaths = 
		    {{{15},          {11,15},       {9,11,15},     {9,10,11},     {9,10,14},     {10,14},       {14}          },
		     {{1,15},        {1,11},        {1,9,15},      {10,11},       {8,9,14},      {8,10},        {8,14}        },
		     {{0,1,15},      {0,11,15},     {0,1,9,11,15}, {9,10,11},     {6,8,9,10,14}, {6,10,14},     {6,8,14}      },
		     {{0,1,2},       {1,2},         {0,1,2},       {},            {6,7,8},       {7,8},         {6,7,8}       },
		     {{0,2,12},      {0,4,12},      {0,2,3,4,12},  {3,4,5},       {3,5,6,7,13},  {5,6,13},      {6,7,13}      },
		     {{2,12},        {2,4},         {2,3,12},      {4,5},         {3,7,13},      {5,7},         {7,13}        },
		     {{12},          {4,12},        {3,4,12},      {3,4,5},       {3,5,13},      {5,13},        {13}          }};
	
//	private static void tmp() {
//		MapLocation center = new MapLocation(0, 0);
//		for (int ex = -3; ex <= +3; ex++) {
//			System.out.print("{");
//			for (int ey = -3; ey <= +3; ey++) {
//				MapLocation enemyLoc = new MapLocation(ex, ey);
//				ArrayList<Integer> blocked = new ArrayList<Integer>();
//				for (int i = 0; i < twoMoveSelfDestructPaths.length; i++) {
//					MapLocation moveLoc = center.add(twoMoveSelfDestructPaths[i][0]).add(twoMoveSelfDestructPaths[i][1]);
//					if (moveLoc.equals(enemyLoc)) blocked.add(i);
//				}
//				String numbers = "";
//				if(blocked.size() > 0) numbers = "" + blocked.get(0);
//				else numbers = "-1";
//				System.out.print(numbers);
//				if (ey < 3) System.out.print(",");
//				int spaces = 3 - numbers.length();
//				for (int i = 0; i < spaces; i++) {
//					System.out.print(" ");
//				}
//			}
//			System.out.println("}");
//		}
//	}
	//blockedTwoMoveSelfDestructPath[3+dx][3+dy] is the index of the twoMoveSelfDestructPath that ends on 
	//(here.x + dx, here.y + dy), or -1 if there is no such path.
	private static int[][] blockedTwoMoveSelfDestructPath = 
		{{-1, -1, -1, -1, -1, -1, -1 },
         {-1, 15, 11, 9,  10, 14, -1 },
         {-1, 1,  -1, -1, -1, 8,  -1 },
         {-1, 0,  -1, -1, -1, 6,  -1 },
         {-1, 2,  -1, -1, -1, 7,  -1 },
         {-1, 12, 4,  3,  5,  13, -1 },
         {-1, -1, -1, -1, -1, -1, -1 }};
    // @formatter:on

	// @formatter:off
	// selfDestructChargeWillWork[numTurns][actionDelayFloor] tells whether we can successfully charge an 
	// enemy with floor(actionDelay) = actionDelayFloor if our charge will take numTurns turns
	private static boolean[][] selfDestructChargeWillWork = 
		{{ true,  true,  true,  true,  true,  true,  true},
		 {false, false,  true,  true,  true,  true,  true},
		 { true,  true, false,  true,  true,  true,  true},
		 {false, false,  true, false,  true,  true,  true},
		 { true,  true, false,  true, false,  true,  true}};	
	// @formatter:on

	// @formatter:off
//	private static void tmp() {
//		MapLocation center = new MapLocation(0, 0);
//		for (int ex = -3; ex <= +3; ex++) {
//			System.out.print("{");
//			for (int ey = -3; ey <= +3; ey++) {
//				MapLocation enemyLoc = new MapLocation(ex, ey);
//				ArrayList<Integer> attacked = new ArrayList<Integer>();
//				for (int dir = 0; dir < 8; dir++) {
//					MapLocation moveLoc = center.add(Direction.values()[dir]);
//					if (moveLoc.isAdjacentTo(enemyLoc)) attacked.add(dir);
//				}
//				System.out.print("{");
//				for (int i = 0; i < attacked.size(); i++) {
//					System.out.print(attacked.get(i));
//					if (i < attacked.size() - 1) System.out.print(",");
//				}
//				System.out.print("}");
//				if (ey < +5) {
//					System.out.print(",");
//					int spaces = Math.min(16, 17 - 2 * attacked.size());
//					for (int i = 0; i < spaces; i++)
//						System.out.print(" ");
//				}
//			}
//			System.out.println("}");
//		}
//	}
	// selfDestructNotes[3+dx][3+dy] is a list of directions such that one move in that direction brings you adjacent to
	// an enemy at (here.x + dx, here.y + dy)
    private static final int[][][] selfDestructNotes = {{{},                {},                {},                {},                {},                {},                {},                },
                                                        {{},                {7},               {6,7},             {5,6,7},           {5,6},             {5},               {},                },
                                                        {{},                {0,7},             {0,6},             {0,4,5,7},         {4,6},             {4,5},             {},                },
                                                        {{},                {0,1,7},           {1,2,6,7},         {0,1,2,3,4,5,6,7}, {2,3,5,6},         {3,4,5},           {},                },
                                                        {{},                {0,1},             {0,2},             {0,1,3,4},         {2,4},             {3,4},             {},                },
                                                        {{},                {1},               {1,2},             {1,2,3},           {2,3},             {3},               {},                },
                                                        {{},                {},                {},                {},                {},                {},                {},                }};
	// @formatter:on

	private static boolean tryMoveForSelfDestruct() throws GameActionException {
		double maxDamage = GameConstants.SELF_DESTRUCT_BASE_DAMAGE + GameConstants.SELF_DESTRUCT_DAMAGE_FACTOR * health;
		double[] moveScores = new double[8];
		for (RobotInfo info : attackableEnemies) {
			if (info.type != RobotType.SOLDIER || info.isConstructing) continue;
			// if (info.actionDelay >= 2) { // otherwise they can flee
			double score = Math.min(maxDamage, info.health);
			int[] moves = selfDestructNotes[3 + info.location.x - here.x][3 + info.location.y - here.y];
			for (int dir : moves) {
				moveScores[dir] += score;
			}
			// }
		}
		double bestScore = 1.5 * health;
		int bestDir = -1;
		Direction[] dirs = Direction.values();
		for (int i = 8; i-- > 0;) {
			if (moveScores[i] > bestScore && rc.canMove(dirs[i])) {
				bestScore = moveScores[i];
				bestDir = i;
			}
		}
		if (bestDir != -1) {
			// make sure we won't hit an ally or step into HQ attack range
			MapLocation dest = here.add(dirs[bestDir]);
			if (!Bot.isInTheirHQAttackRange(dest) && rc.senseNearbyGameObjects(Robot.class, dest, 2, us).length == 0) {
				// check if we think we can survive the charge
				int numAttacksSuffered = 0;
				double[] targetHealths = new double[99];
				int numTargets = 0;
				for (RobotInfo enemy : visibleEnemies) {
					if (enemy.type != RobotType.SOLDIER || enemy.isConstructing) continue;
					if (enemy.location.distanceSquaredTo(dest) <= RobotType.SOLDIER.attackRadiusMaxSquared) {
						if (enemy.actionDelay < 2) numAttacksSuffered++;
						if (enemy.location.isAdjacentTo(dest)) {
							targetHealths[numTargets++] = enemy.health;
						}
					}
				}
				double damageTaken = numAttacksSuffered * RobotType.SOLDIER.attackPower;
				if (health > damageTaken) {
					double actualMaxDamage = GameConstants.SELF_DESTRUCT_BASE_DAMAGE + GameConstants.SELF_DESTRUCT_DAMAGE_FACTOR * (health - damageTaken);
					double actualTotalDamageDealt = 0;
					int numKills = 0;
					for (int i = 0; i < numTargets; i++) {
						double targetHealth = targetHealths[i];
						if (targetHealth <= actualMaxDamage) {
							actualTotalDamageDealt += targetHealth;
							numKills++;
						} else {
							actualTotalDamageDealt += actualMaxDamage;
						}
					}
					if (actualTotalDamageDealt > health && (numKills > 0 || stance == MicroStance.AGGRESSIVE)) {
						rc.move(dirs[bestDir]);
						// Tell others not to go for a self-destruct until the round after we self-destruct
						MessageBoard.SELF_DESTRUCT_LOCKOUT_ID.writeInt(rc.getRobot().getID());
						MessageBoard.SELF_DESTRUCT_LOCKOUT_ROUND.writeInt(Clock.getRoundNum() + 2);
						return true;
					}
				}
			}
		}
		return false;
	}

	private static boolean tryMoveForTwoMoveSelfDestruct() throws GameActionException {
		double maxDamage = GameConstants.SELF_DESTRUCT_BASE_DAMAGE + GameConstants.SELF_DESTRUCT_DAMAGE_FACTOR * health;
		int numPaths = twoMoveSelfDestructPaths.length;
		boolean onRoad = rc.senseTerrainTile(here) == TerrainTile.ROAD;
		int chargeTurns = onRoad ? 2 : 3; // how many turns our charge will take. This assumes we have small actiondelay for diagonal charges
		boolean[] actionDelayIsVulnerable = selfDestructChargeWillWork[chargeTurns]; // which enemy actiondelays are vulnerable to a charge
		double[] pathScores = new double[numPaths];
		for (RobotInfo info : visibleEnemies) {
			if (info.type != RobotType.SOLDIER || info.isConstructing) continue;
			if (actionDelayIsVulnerable[(int) info.actionDelay]) { // check if this enemy will be able to flee
				int enemyDxP3 = info.location.x - here.x + 3;
				int enemyDyP3 = info.location.y - here.y + 3;
				if (enemyDxP3 >= 0 && enemyDxP3 <= 6 && enemyDyP3 >= 0 && enemyDyP3 <= 6) {
					int blockedPath = blockedTwoMoveSelfDestructPath[enemyDxP3][enemyDyP3];
					if (blockedPath != -1) pathScores[blockedPath] = -999999;
					double score = Math.min(maxDamage, info.health);
					int[] paths = twoMoveSelfDestructTargetPaths[enemyDxP3][enemyDyP3];
					for (int p : paths) {
						pathScores[p] += score;
					}
				}
			}
		}
		double bestScore = 1.5 * health;
		int bestPath = -1;
		for (int i = 0; i < numPaths; i++) {
			if (pathScores[i] > bestScore) {
				Direction[] path = twoMoveSelfDestructPaths[i];
				Direction move1 = path[0];
				if (rc.canMove(move1)) {
					Direction move2 = path[1];
					if (Util.passable(rc.senseTerrainTile(here.add(move1).add(move2)))) {
						bestScore = pathScores[i];
						bestPath = i;
					}
				}
			}
		}
		if (bestPath != -1) {
			Direction[] path = twoMoveSelfDestructPaths[bestPath];
			Direction move1 = path[0];
			Direction move2 = path[1];
			MapLocation dest = here.add(move1).add(move2);

			if (Bot.isInTheirHQAttackRange(dest)) return false;

			double totalActionDelay = twoMoveSelfDestructPathActionDelay[bestPath];
			if (rc.senseTerrainTile(here) == TerrainTile.ROAD) totalActionDelay *= GameConstants.ROAD_ACTION_DELAY_FACTOR;
			totalActionDelay += rc.getActionDelay();

			// If waiting a turn to recover action delay will not slow us down, then wait: it just lets us avoid
			// taking unnecessary hits
			if (totalActionDelay - (int) totalActionDelay < rc.getActionDelay()) return true;

			int numVulnerableTurns = 1 + (int) totalActionDelay;

			// check if we think we can survive the charge
			int numAttacksSuffered = 0;
			double[] targetHealths = new double[99];
			int numTargets = 0;
			for (RobotInfo enemy : visibleEnemies) {
				if (enemy.type != RobotType.SOLDIER || enemy.isConstructing) continue;
				if (enemy.location.distanceSquaredTo(dest) <= RobotType.SOLDIER.attackRadiusMaxSquared) {
					// magic formula, I hope it's right:
					numAttacksSuffered += (2 + numVulnerableTurns - (enemy.actionDelay < 1 ? 1 : (int) enemy.actionDelay)) / 2;
					if (enemy.location.isAdjacentTo(dest)) {
						targetHealths[numTargets++] = enemy.health;
					}
				}
			}
			double damageTaken = numAttacksSuffered * RobotType.SOLDIER.attackPower;
			// Debug.indicate("selfdestruct", 0,
			// String.format("considering self-destruct: numVulnerableTurrns = %d, numAttacksSuffered = %d,", numVulnerableTurns, numAttacksSuffered));
			// Debug.indicate("selfdestruct", 1, String.format("health = %f, damageTaken = %f", health, damageTaken));
			if (health > damageTaken) {
				double actualMaxDamage = GameConstants.SELF_DESTRUCT_BASE_DAMAGE + GameConstants.SELF_DESTRUCT_DAMAGE_FACTOR * (health - damageTaken);
				double actualTotalDamageDealt = 0;
				int numKills = 0;
				for (int i = 0; i < numTargets; i++) {
					double targetHealth = targetHealths[i];
					if (targetHealth <= actualMaxDamage) {
						actualTotalDamageDealt += targetHealth;
						numKills++;
					} else {
						actualTotalDamageDealt += actualMaxDamage;
					}
				}
				if (actualTotalDamageDealt >= 1.3 * health && (numKills > 0 || stance == MicroStance.AGGRESSIVE)) {
					// Debug.indicate("selfdestruct", 2,
					// String.format("actualTotalDamageDealt = %f, numKills = %d: going for it", actualTotalDamageDealt, numKills));
					rc.move(twoMoveSelfDestructPaths[bestPath][0]);
					// Tell others not to go for a self-destruct right now
					MessageBoard.SELF_DESTRUCT_LOCKOUT_ID.writeInt(rc.getRobot().getID());
					MessageBoard.SELF_DESTRUCT_LOCKOUT_ROUND.writeInt(Clock.getRoundNum() + 2);
					return true;
				} else {
					// Debug.indicate("selfdestruct", 2, String.format("actualTotalDamageDealt = %f, numKills = %d: too little", actualTotalDamageDealt,
					// numKills));
				}
			}
		}
		return false;
	}

	private static boolean tryMovetoEngageUndefendedPastr() throws GameActionException {
		boolean canSeePastr = false;
		MapLocation pastrLoc = null;
		for (int i = visibleEnemies.length; i-- > 0;) {
			if (visibleEnemies[i].type == RobotType.PASTR) {
				canSeePastr = true;
				pastrLoc = visibleEnemies[i].location;
				if (here.distanceSquaredTo(pastrLoc) <= RobotType.SOLDIER.attackRadiusMaxSquared) return false; // we can shoot a pastr, we don't need to move
			}
		}
		if (!canSeePastr) return false;

		Direction toPastr = here.directionTo(pastrLoc);
		if (!rc.canMove(toPastr)) return false;

		MapLocation pathLoc = here;
		while (pathLoc.distanceSquaredTo(pastrLoc) > RobotType.SOLDIER.attackRadiusMaxSquared) {
			pathLoc = pathLoc.add(pathLoc.directionTo(pastrLoc));
			if (rc.senseTerrainTile(pathLoc) == TerrainTile.VOID) return false;
			if (isInTheirHQAttackRange(pathLoc)) return false;
			Robot[] enemyRobotsAlongPath = rc.senseNearbyGameObjects(Robot.class, pathLoc, RobotType.SOLDIER.attackRadiusMaxSquared, them);
			if (Util.containsNonConstructingSoldier(enemyRobotsAlongPath, rc)) return false;
		}

		rc.move(toPastr);
		return true;
	}

	private static boolean tryMoveToEngageAnyUndefendedHelplessEnemy() throws GameActionException {
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
			if (sneak == Nav.Sneak.NO) rc.move(tryDir);
			else rc.sneak(tryDir);
			return true;
		}
		// Debug.indicate("micro", 1, String.format("can't move toward %s with max enemy exposure %d", dest.toString(), maxEnemyExposure));
		return false;
	}

	private static boolean existsEnemyAttackingLocation(MapLocation loc) {
		for (int j = visibleEnemies.length; j-- > 0;) {
			RobotInfo info = visibleEnemies[j];
			if (info.location.distanceSquaredTo(loc) <= RobotType.SOLDIER.attackRadiusMaxSquared) {
				if (info.type == RobotType.SOLDIER && !info.isConstructing) return true;
			}
		}
		return false;
	}

	private static boolean tryMoveTowardLocationWithNoEnemyExposureWithinRadius(MapLocation dest, MapLocation center, int maxRadiusSq, Nav.Sneak sneak)
			throws GameActionException {
		Direction toEnemy = here.directionTo(dest);
		Direction[] tryDirs = new Direction[] { toEnemy, toEnemy.rotateLeft(), toEnemy.rotateRight() };
		for (int i = 0; i < tryDirs.length; i++) {
			Direction tryDir = tryDirs[i];
			if (!rc.canMove(tryDir)) continue;
			MapLocation tryLoc = here.add(tryDir);
			if (existsEnemyAttackingLocation(tryLoc)) continue;
			if (center.distanceSquaredTo(here.add(tryDir)) > maxRadiusSq) continue;
			if (isInTheirHQAttackRange(here.add(tryDir))) continue;

			if (sneak == Nav.Sneak.NO) rc.move(tryDir);
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

	private static int numOtherAlliedSoldiersAndBuildingsInAttackRange(MapLocation loc) throws GameActionException {
		return rc.senseNearbyGameObjects(Robot.class, loc, RobotType.SOLDIER.attackRadiusMaxSquared, us).length;
	}

	private static int numOtherNonSelfDestructingAlliedSoldiersAndBuildingsInAttackRange(MapLocation loc) throws GameActionException {
		Robot[] allies = rc.senseNearbyGameObjects(Robot.class, loc, RobotType.SOLDIER.attackRadiusMaxSquared, us);
		if (Clock.getRoundNum() > MessageBoard.SELF_DESTRUCT_LOCKOUT_ROUND.readInt()) {
			return allies.length;
		} else {
			int ret = 0;
			int selfDestructorID = MessageBoard.SELF_DESTRUCT_LOCKOUT_ID.readInt();
			for (Robot ally : allies) {
				if (ally.getID() != selfDestructorID) ret++;
			}
			return ret;
		}
	}

	private static int numOtherNonConstructingAlliedSoldiersInAttackRange(MapLocation loc) throws GameActionException {
		return numOtherNonConstructingAlliedSoldiersInRange(loc, RobotType.SOLDIER.attackRadiusMaxSquared);
	}

	private static int numOtherNonConstructingAlliedSoldiersInRange(MapLocation loc, int rangeSq) throws GameActionException {
		int numAlliedSoldiers = 0;
		Robot[] allies = rc.senseNearbyGameObjects(Robot.class, loc, rangeSq, us);
		for (int i = allies.length; i-- > 0;) {
			RobotInfo info = rc.senseRobotInfo(allies[i]);
			if (info.type == RobotType.SOLDIER && !info.isConstructing) numAlliedSoldiers++;
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
			int numNearbyAllies = 1 + numOtherNonConstructingAlliedSoldiersInAttackRange(info.location);
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
			RobotType targetType = info.type;
			if (targetType == bestType) { // break ties between equal types by health
				double targetHealth = info.health;
				if (targetHealth < bestHealth) {
					bestHealth = targetHealth;
					ret = info;
				}
			} else if (targetType == RobotType.PASTR || bestType == RobotType.SOLDIER) { // prefer pastrs to noise towers to constructing soldiers
				bestType = targetType;
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
		if (!inHealingState || attackableEnemies.length == 1) {
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

	private static void fleeWithLowHealth() throws GameActionException {
		Direction dir = chooseRetreatDirection();
		if (dir != null) {
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
		if (cachedNumEnemiesAttackingMoveDirs == null) {
			cachedNumEnemiesAttackingMoveDirs = new int[8];
			for (int i = visibleEnemies.length; i-- > 0;) {
				RobotInfo info = visibleEnemies[i];
				if (info.type == RobotType.SOLDIER && !info.isConstructing) {
					MapLocation enemyLoc = info.location;
					int[] attackedDirs = attackNotes[5 + enemyLoc.x - here.x][5 + enemyLoc.y - here.y];
					for (int j = attackedDirs.length; j-- > 0;) {
						cachedNumEnemiesAttackingMoveDirs[attackedDirs[j]]++;
					}
				}
			}
		}
		return cachedNumEnemiesAttackingMoveDirs;
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
			if (minEnemyDistSq > RobotType.SOLDIER.attackRadiusMaxSquared) {
				return tryDir; // we can escape!!
			}
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
		int smallestAllyDistSq = 999999;
		for (int i = numPastrLocations; i-- > 0;) {
			int distSq = here.distanceSquaredTo(bestPastrLocations[i]);
			if (distSq < smallestAllyDistSq) smallestAllyDistSq = distSq;
		}
		return smallestEnemyDistSq < smallestAllyDistSq;
	}

	private static void attackAndRecord(RobotInfo enemyInfo) throws GameActionException {
		if (enemyInfo == null) return; // should never happen, but just to be sure
		rc.attackSquare(enemyInfo.location);
		// if (enemyInfo.health <= RobotType.SOLDIER.attackPower) MessageBoard.ROUND_KILL_COUNT.incrementInt();
	}

}
