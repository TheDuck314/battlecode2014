package anatid;

import battlecode.common.*;

public class BotSoldier extends Bot {
	public BotSoldier(RobotController theRC) throws GameActionException {
		super(theRC);
		Debug.init(theRC, "nav");
		Nav.init(theRC);

		spawnOrder = MessageBoard.SPAWN_COUNT.readInt();
		// Debug.indicate("buildorder", 0, "I am robot #" + spawnOrder);
	}

	private enum MicroStance {
		DEFENSIVE, AGGRESSIVE, HARRASS
	}

	MicroStance stance; // how to behave in combat
	Nav.Engage navEngage; // whether to engage enemies during navigation

	RobotInfo[] visibleEnemies; // enemies within vision radius (35)
	RobotInfo[] attackableEnemies; // enemies within attack radius(10)
	int spawnOrder;

	public void turn() throws GameActionException {
		if (!rc.isActive()) return;
		if (rc.getConstructingRounds() > 0) return; // can't do anything while constructing; is this redundant?

		Strategy.active = MessageBoard.STRATEGY.readStrategy();
		if (Strategy.active == Strategy.UNDECIDED) return;

		if (Strategy.active == Strategy.HQ_PASTR) {
			if (doHQPastrStratBuilding()) return;
		}

		updateEnemyData();
		MapLocation attackTarget = MessageBoard.ATTACK_LOC.readMapLocation();
		stance = chooseMicroStance(attackTarget);
		navEngage = stance == MicroStance.AGGRESSIVE ? Nav.Engage.YES : Nav.Engage.NO;

		if (mercyKillPastrs()) return;

		// If there are enemies in attack range, fight!
		if (attackableEnemies.length > 0 && rc.isActive()) {
			fight();
			return;
		}

		// If we are in attack mode, move toward the attack target and engage.
		if (attackTarget != null) {
			if (attackTarget.distanceSquaredTo(theirHQ) <= 5 && here.distanceSquaredTo(theirHQ) <= 35) {
				harrassTheirHQ();
				return;
			} else {
				Nav.goTo(attackTarget, Nav.Sneak.NO, navEngage);
				// If we didn't move in Nav, try fighting:
				if (rc.isActive() && visibleEnemies.length > 0) fight();
				return;
			}
		}

		if (Strategy.active == Strategy.NOISE_THEN_ONE_PASTR || Strategy.active == Strategy.ONE_PASTR_THEN_NOISE) {
			// See if we should build something
			if (tryBuildNoiseTower()) return;

			// Build or defend a pastr
			buildOrDefendPastr();
		}
	}

	// Decide whether to behave aggressively or defensively. Only be aggressive if we are in attack mode
	// and there is a decent number of allies around, or if we are in any mode and we have a big numbers advantage
	private MicroStance chooseMicroStance(MapLocation attackTarget) throws GameActionException {
		if (Strategy.active == Strategy.HQ_PASTR) return MicroStance.HARRASS;

		if (visibleEnemies.length == 0) {
			return MicroStance.AGGRESSIVE; // stance doesn't matter if there are no enemies
		} else {
			int numAllies = 1; // us
			numAllies += Math.max(numOtherAlliedSoldiersInRange(here, RobotType.SOLDIER.sensorRadiusSquared),
					numOtherAlliedSoldiersInRange(Util.closest(visibleEnemies, here), 16));

			if (attackTarget == null) {
				if (numAllies >= visibleEnemies.length * 2 || numAllies > visibleEnemies.length + 3) return MicroStance.AGGRESSIVE;
				else return MicroStance.DEFENSIVE;
			} else {
				if (numAllies >= 2 && numAllies >= visibleEnemies.length - 1) return MicroStance.AGGRESSIVE;
				else return MicroStance.DEFENSIVE;
			}
		}
	}

	private boolean doHQPastrStratBuilding() throws GameActionException {
		if (spawnOrder == 1) {
			MapLocation pastrLoc = ourHQ.add(ourHQ.directionTo(here).rotateRight().rotateRight());
			constructAndAdvertiseNoiseTower(pastrLoc);
			return true;
		}
		if (spawnOrder == 2) {
			// a trick: make the pastr go up just after the noise tower
			// which case make the pastr immediately. Keeps the enemy guessing for an extra ~20 rounds and doesn't
			// sacrifice any cows
			if (Clock.getRoundNum() < 80) return true;
			else rc.construct(RobotType.PASTR);
			return true;
		}
		// Check if the initial pastr or noise tower got destroyed. If so, rebuild it if we are adjacent to the HQ
		if (Clock.getRoundNum() > 200 && here.isAdjacentTo(ourHQ) && !here.directionTo(ourHQ).isDiagonal()) {
			if (rc.sensePastrLocations(us).length == 0) {
				int otherRebuildRoundStart = MessageBoard.REBUILDING_HQ_PASTR_ROUND_START.readInt();
				if (Clock.getRoundNum() > 2 + RobotType.PASTR.captureTurns + otherRebuildRoundStart) {
					MessageBoard.REBUILDING_HQ_PASTR_ROUND_START.writeInt(Clock.getRoundNum());
					rc.construct(RobotType.PASTR);
					return true;
				}
			}
			MapLocation noiseTowerLoc = MessageBoard.NOISE_TOWER_BUILD_LOCATION.readMapLocation();
			if (rc.senseNearbyGameObjects(Robot.class, noiseTowerLoc, 0, us).length == 0) {
				MapLocation[] ourPastrs = rc.sensePastrLocations(us);
				MapLocation pastrLoc = ourPastrs.length > 0 ? ourPastrs[0] : ourHQ;
				constructAndAdvertiseNoiseTower(pastrLoc);
				return true;
			}
		}
		return false;
	}

	int pastrDelayFinesseWaitRoundsRemaining = 32;

	private void buildOrDefendPastr() throws GameActionException {
		MapLocation desiredPastrLoc = MessageBoard.BEST_PASTR_LOC.readMapLocation();
		if (desiredPastrLoc != null) {
			if (here.equals(desiredPastrLoc)) {
				// We get to build the pastr!
				if (rc.isActive()) {
					// a trick: make the pastr go up just after the noise tower, if we're already building a noise tower
					// which case make the pastr immediately. Keeps the enemy guessing for an extra ~20 rounds and doesn't
					// sacrifice any cows
					// First, make sure the noise tower builder is still alive:
					MapLocation noiseTowerLoc = MessageBoard.NOISE_TOWER_BUILD_LOCATION.readMapLocation();
					if (noiseTowerLoc != null) {
						if (numOtherAlliedSoldiersInRange(noiseTowerLoc, 0) == 1) {
							// Next, determine when construction started
							int noiseTowerBuildStartRound = MessageBoard.NOISE_TOWER_BUILD_START_ROUND.readInt();
							// Start construction 80 rounds after that. Before that, sit tight:
							if (Clock.getRoundNum() < 80 + noiseTowerBuildStartRound) {
								return;
							}
						}
					} else {
						// no one's building a noise tower yet. Wait some time in case someone is about to start
						// Also, we won't start the noise tower build until at least turn 81 since that is when HQ
						// tower people set up their pastr, so wait at least until then
						pastrDelayFinesseWaitRoundsRemaining--;
						// Debug.indicate("finesse", 0, "rounds remaining = " + pastrDelayFinesseWaitRoundsRemaining);
						if (Clock.getRoundNum() <= 85 || pastrDelayFinesseWaitRoundsRemaining > 0) return;
					}
					// Either we've given up on the trick or the trick delay is over:
					rc.construct(RobotType.PASTR);
					return;
				}
			} else {
				// Go to the pastr location and fight if necessary
				int distSq = here.distanceSquaredTo(desiredPastrLoc);
				if (distSq <= 8 && visibleEnemies.length > 0) {
					if (rc.isActive()) fight();
				} else {
					Nav.Sneak navSneak = distSq <= 30 && visibleEnemies.length == 0 && rc.sensePastrLocations(us).length > 0 ? Nav.Sneak.YES : Nav.Sneak.NO;
					Nav.goTo(desiredPastrLoc, navSneak, navEngage);

					// If we didn't move, consider fighting
					if (rc.isActive() && visibleEnemies.length > 0) fight();
				}
			}
		}
	}

	private void constructAndAdvertiseNoiseTower(MapLocation pastrLoc) throws GameActionException {
		MessageBoard.NOISE_TOWER_BUILD_LOCATION.writeMapLocation(here);
		MessageBoard.NOISE_TOWER_BUILD_START_ROUND.writeInt(Clock.getRoundNum());
		rc.construct(RobotType.NOISETOWER);
		HerdPattern.computeAndPublish(here, pastrLoc, rc);
	}

	// After a little while, if nothing has gone wrong, if we are next to a pastr and no one
	// else is building or has built a noise tower, build one.
	private boolean tryBuildNoiseTower() throws GameActionException {
		if (!rc.isActive()) return false;

		if (Strategy.active == Strategy.HQ_PASTR) {
			// in this strategy, we should build a noise tower if the previous one was destroyed
			// and we are next to the HQ
			if (here.isAdjacentTo(ourHQ)) {
				// Check where the original noise tower was
				MapLocation existingBuilder = MessageBoard.NOISE_TOWER_BUILD_LOCATION.readMapLocation();
				if (existingBuilder != null) {
					// If the existing builder is still around, don't build a noise tower
					if (!here.equals(existingBuilder) && rc.senseNearbyGameObjects(Robot.class, existingBuilder, 0, us).length > 0) {
						return false;
					}
				}
				// Find where our pastr is
				MapLocation[] ourPastrs = rc.sensePastrLocations(us);
				MapLocation pastrLoc = ourPastrs.length > 0 ? ourPastrs[0] : ourHQ;
				// build the noise tower
				constructAndAdvertiseNoiseTower(pastrLoc);
				return true;
			}
		}

		// Only allowed to build noise tower if adjacent to pastr
		MapLocation pastrLoc = MessageBoard.BEST_PASTR_LOC.readMapLocation();
		if (pastrLoc == null) return false;
		if (!here.isAdjacentTo(pastrLoc)) return false;

		switch (Strategy.active) {
			case ONE_PASTR_THEN_NOISE:
				// In this strategy, we are cautious and don't build a noise tower until they build a pastr
				// or until a certain number of rounds have elapsed. This is to try to defend better against
				// rushes
				int numEnemyPastrs = rc.sensePastrLocations(them).length;
				if (Clock.getRoundNum() < 300 && numEnemyPastrs == 0) return false;

				// Usually we don't want to build a noise tower until one of our bots has made it to our pastr
				// location. However, we can build the noise tower if they have put up a pastr. Said another way,
				// *don't* build the noise tower if they have no pastrs and no one is at our pastr location
				if (numEnemyPastrs == 0 && rc.senseNearbyGameObjects(Robot.class, pastrLoc, 0, us).length == 0) return false;
				break;

			case NOISE_THEN_ONE_PASTR:
				// no such restriction exists for this strategy
				break;

			default: // this shouldn't happen
				break;
		}

		// Check if someone else is already building a noise tower
		MapLocation existingBuilder = MessageBoard.NOISE_TOWER_BUILD_LOCATION.readMapLocation();
		if (existingBuilder != null) {
			// Check if that bot has actually been destroyed
			if (rc.senseNearbyGameObjects(Robot.class, existingBuilder, 0, us).length > 0) {
				return false;
			}
		}

		// Construct the noise tower and advertise the fact that we are doing it
		constructAndAdvertiseNoiseTower(pastrLoc);
		return true;
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

	// @formatter:off
	// Micro strategy:
	//   Suppose we are in attack range of an enemy soldier. Then there are a few things we might
	// want to do:
	//   * Attack someone. Experiment with targeting based on num allies that can attack, vs lowest health
	//   * Retreat. This is probably a good idea if and only if we are outnumbered. This requires figuring out a good
	//     retreat square. Do this by figuring out which adjacent squares are attacked by enemies, and then going to 
	//     the one with the fewest enemies. Break ties by number of nearby allies?
	//   * Reposition. This could be good for setting up double-teams. For example, in a 2x2 if we waste 1 attack to
	//     set up a double team, then we sacrifice 10 damage, but we'll kill an enemy 5 attacks sooner, saving ourself
	//     50 damage. Maybe very worthwhile?
	//
	//   Suppose we are not in attack range of an enemy soldier, but we can see an enemy soldier. Then we need
	// to decide whether to engage. Engaging first always exposes you to fire, except maybe in the special case where 
	// the enemy has just moved diagonally and 3 rounds until he can attack. Then if you can engage with an orthogonal
	// movement you get a 1-round head start and can win a 1v1.
	//   Presumably we should never walk into a square from which more than 1 enemy can attack us. We should also think
	// hard before engaging with a diagonal movement. 
	//
	//   The above is fairly cautious, and assumes we aren't desperate. Engaging first will always result in worse outcomes
	// than letting the enemy engage first, all other things being equal. Sometimes we simply must attack, though--for example
	// if we are losing on milk and need to kill pastrs. An opponent with bad micro may engage first but one with good
	// good defensive micro will make us come to him. So we probably need several different micro strategies:
    //
	//   - DEFENSIVE: never engage first. Maybe be more willing to retreat?
	//   - MEDIUM: engage 1v1, but never 1v2
	//   - AGGRESSIVE: willing to enter 1v2s if enough allies are nearby and we have a decent chance of 
	//                 overcoming the disadvantage of being the attacker.
	//
	//   Even a DEFENSIVE strategy is probably good enough to beat framework1-9noise, so let's try to write that first.
	//
	// In addition to the above considerations, we need to implement a system for telling nearby robots that can't see
	// the enemies where the enemies are, and having those come in to attack.
	//
	// We may want a super simple Nav system for combat, that doesn't do any bug or anything and just does greedy movements
	// On the other hand this would probably get super confused by combat across walls.
	// @formatter:on

	// Notes:
	// attack range is 10
	// vision range is 35
	// range of squares we could attack after one orthogonal movement is 16
	// (so 10 < range <= 16 means we can't attack now, but could after one orthogonal movement)

	// fight() should be called as a result of being forced into combat.
	// After fight(), don't do anything except return.
	private void fight() throws GameActionException {
		// Debug.indicate("micro", 0, String.format("numVisibleEnemies = %d; numAttackableEnemies = %d", visibleEnemies.length, attackableEnemies.length));

		if (attackableEnemies.length != 0) { // There is at least one enemy in attack range (which should hopefully not be their HQ)
			int numAttackableSoldiers = Util.countSoldiers(attackableEnemies);
			if (numAttackableSoldiers >= 1) {
				if (stance == MicroStance.HARRASS) {
					retreatOrFight();
					return;
				} else {
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
							// Debug.indicate("micro", 1, "double-teamed, but so is an enemy: fighting");
							attackASoldier();
							return;
						} else { // no enemy is double-teamed. Retreat!
							// Debug.indicate("micro", 1, "double-teamed: retreating");
							retreatOrFight();
							return;
						}
					} else { // We're not getting double-teamed. We're within attack range of exactly one soldier
						// Fight on if we are winning the 1v1 or if the other guy is double-teamed. Otherwise retreat
						// But if we are in aggressive mode, never retreat!
						// First find the single enemy
						RobotInfo enemySoldier = findASoldier(attackableEnemies);
						// TODO: count of allies should really only count soldiers
						if (stance == MicroStance.AGGRESSIVE || enemySoldier.health <= rc.getHealth() || enemySoldier.constructingRounds > 0
								|| numOtherAlliedSoldiersInAttackRange(enemySoldier.location) >= 1) {
							// Kill him!
							// Debug.indicate("micro", 1, "winning vs single enemy: fighting");
							attackAndRecord(enemySoldier);
							return;
						} else {
							// retreat!
							// Debug.indicate("micro", 1, "losing vs single enemy: retreating (numOtherAllies in range of " + enemySoldier.location.toString()
							// + ") is " + numOtherAlliedSoldiersInAttackRange(enemySoldier.location));
							retreatOrFight();
							return;
						}
					}
				}
			} else { // can't attack a soldier
				boolean canSeeSoldier = Util.countSoldiers(visibleEnemies) > 0;
				if (canSeeSoldier) {
					// handle the case when we can attack a building, but can see a soldier.
					// Need to decide whether to move to attack a soldier or attack the building
					// For the moment, let's just attack a building.
					// TODO: add some code that makes us move to support allies when appropriate
					MapLocation closestSoldier = Util.closestSoldier(visibleEnemies, here);

					int numAlliesFightingClosestEnemySoldier = numOtherAlliedSoldiersInAttackRange(closestSoldier);
					if (numAlliesFightingClosestEnemySoldier > 0) {
						// We deliberately count allied buildings below so that we are more aggressively about engaging enemies who attack our buildings
						int maxEnemyExposure = numAlliesFightingClosestEnemySoldier;
						if (stance == MicroStance.AGGRESSIVE && maxEnemyExposure > 0) maxEnemyExposure++;
						cautiouslyApproachVisibleEnemySoldier(closestSoldier, maxEnemyExposure);
						if (!rc.isActive()) return;
					}
					// Debug.indicate("micro", 1, "can see a soldier, but preferring to attack a building");
					attackABuilding();
					return;
				} else {
					// Can't see any soldiers, but can attack a building. Do it
					// Debug.indicate("micro", 1, "can't see a soldier; attacking a building");
					attackABuilding();
					return;
				}
			}
		} else { // No attackable enemies! But there is at least one visible enemy
			int numVisibleEnemySoldiers = Util.countSoldiers(visibleEnemies);
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
				MapLocation closestSoldier = Util.closestSoldier(visibleEnemies, here);
				// We deliberately count allied buildings below so that we are more aggressively about engaging enemies who attack our buildings
				int maxEnemyExposure = numOtherAlliedUnitsInAttackRange(closestSoldier);
				if (stance == MicroStance.AGGRESSIVE && maxEnemyExposure > 0) maxEnemyExposure++;
				cautiouslyApproachVisibleEnemySoldier(closestSoldier, maxEnemyExposure);
				if (rc.isActive() && stance == MicroStance.HARRASS) {
					harrassToward(closestSoldier);
					return;
				}
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
						if (rc.canMove(tryDir) && !Util.inHQAttackRange(here.add(tryDir), theirHQ)) {
							// Debug.indicate("micro", 1, "moving towards visible building");
							rc.move(tryDir);
							return;
						}
					}
					// Debug.indicate("micro", 1, "ignoring visible building because somehow we can't move");
					return;
				} else { // can only see the enemy HQ.
					// we are not really fighting. Let's move away from it so we can say we did something
					// Debug.indicate("micro", 1, "can only see enemy HQ");
					Direction away = theirHQ.directionTo(here);
					if (rc.canMove(away)) rc.move(away);
					return;
				}
			}
		}
	}

	private void cautiouslyApproachVisibleEnemySoldier(MapLocation enemySoldier, int maxEnemyExposure) throws GameActionException {
		int[] numEnemiesAttackingDirs = countNumEnemiesAttackingMoveDirs();

		Direction toEnemy = here.directionTo(enemySoldier);
		Direction[] tryDirs = new Direction[] { toEnemy, toEnemy.rotateLeft(), toEnemy.rotateRight() };
		for (int i = 0; i < tryDirs.length; i++) {
			Direction tryDir = tryDirs[i];
			if (!rc.canMove(tryDir)) continue;
			if (numEnemiesAttackingDirs[tryDir.ordinal()] > maxEnemyExposure) continue;
			if (Util.inHQAttackRange(here.add(tryDir), theirHQ)) continue;
			// Debug.indicate("micro", 1, String.format("cautiously approaching enemy soldier; direction %d; attackers = %d %d %d %d %d %d %d %d",
			// tryDir.ordinal(), numEnemiesAttackingDirs[0], numEnemiesAttackingDirs[1], numEnemiesAttackingDirs[2], numEnemiesAttackingDirs[3],
			// numEnemiesAttackingDirs[4], numEnemiesAttackingDirs[5], numEnemiesAttackingDirs[6], numEnemiesAttackingDirs[7]));
			rc.move(tryDir);
			return;
		}
		// Debug.indicate("micro", 1, "can't safely approach enemy soldier");
	}

	private void harrassTheirHQ() throws GameActionException {
		harrassToward(theirHQ);
	}

	private void harrassToward(MapLocation enemySoldier) throws GameActionException {
		Direction toEnemy = here.directionTo(enemySoldier);

		boolean closerToTheirHQ = here.distanceSquaredTo(theirHQ) < here.distanceSquaredTo(ourHQ);
		if (closerToTheirHQ) {
			if (tryToKillCows()) return;
		}

		if (closerToTheirHQ && FastRandom.randInt(4) == 0 && numOtherAlliedSoldiersInRange(here.add(toEnemy, 2), 1) == 0) {
			rc.attackSquare(here.add(toEnemy, 2));
			return;
		}

		int[] numEnemiesAttackingDirs = countNumEnemiesAttackingMoveDirs();

		Direction[] tryDirs;
		Robot[] alliedRobots = rc.senseNearbyGameObjects(Robot.class, here, RobotType.SOLDIER.sensorRadiusSquared, us);
		RobotInfo[] allies = Util.senseAllInfos(alliedRobots, rc);
		MapLocation closestAlly = Util.closest(allies, here);
		if (closestAlly == null) {
			tryDirs = new Direction[] { toEnemy, toEnemy.rotateLeft(), toEnemy.rotateRight(), toEnemy.rotateLeft().rotateLeft(),
					toEnemy.rotateRight().rotateRight(), toEnemy.rotateLeft().rotateLeft().rotateLeft(), toEnemy.rotateRight().rotateRight().rotateRight() };
		} else {
			Direction repel = closestAlly.directionTo(here);
			tryDirs = new Direction[] { repel, repel, repel, repel, toEnemy, toEnemy.rotateLeft(), toEnemy.rotateRight(), toEnemy.rotateLeft().rotateLeft(),
					toEnemy.rotateRight().rotateRight(), toEnemy.rotateLeft().rotateLeft().rotateLeft(), toEnemy.rotateRight().rotateRight().rotateRight() };
		}
		for (int t = 0; t < 3; t++) {
			int i = FastRandom.randInt(tryDirs.length);
			Direction tryDir = tryDirs[i];
			if (!rc.canMove(tryDir)) continue;
			if (numEnemiesAttackingDirs[tryDir.ordinal()] > 0) continue;
			if (Util.inHQAttackRange(here.add(tryDir), theirHQ)) continue;
			// Debug.indicate("micro", 1, String.format("harassing enemy soldier; direction %d; attackers = %d %d %d %d %d %d %d %d", tryDir.ordinal(),
			// numEnemiesAttackingDirs[0], numEnemiesAttackingDirs[1], numEnemiesAttackingDirs[2], numEnemiesAttackingDirs[3], numEnemiesAttackingDirs[4],
			// numEnemiesAttackingDirs[5], numEnemiesAttackingDirs[6], numEnemiesAttackingDirs[7]));
			rc.move(tryDir);
			return;
		}
		if (closerToTheirHQ) rc.attackSquare(here.add(toEnemy, 2));
		// Debug.indicate("micro", 1, "can't safely approach enemy soldier");
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
		// Debug.indicate("micro", 2, "trying to kill cows: mostCows = " + mostCows + " at " + (bestTarget == null ? "null" : bestTarget.toString()));
		if (mostCows > 300) {
			rc.attackSquare(bestTarget);
			return true;
		} else {
			return false;
		}
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

	private RobotInfo findASoldier(RobotInfo[] infos) {
		for (int i = infos.length; i-- > 0;) {
			if (infos[i].type == RobotType.SOLDIER) {
				return infos[i];
			}
		}
		return null; // should never happen
	}

	// Assumes attackableEnemies contains a soldier
	private void attackASoldier() throws GameActionException {
		RobotInfo target = chooseSoldierAttackTarget(attackableEnemies);
		attackAndRecord(target);
	}

	// Assumes enemies list contains at least one soldier!
	// TODO: focus on robots with constructingRounds == 0? This seems like a rare case and maybe not worth it
	private RobotInfo chooseSoldierAttackTarget(RobotInfo[] enemies) throws GameActionException {
		RobotInfo ret = null;
		double bestNumNearbyAllies = -1;
		double bestHealth = 999999;
		double bestActionDelay = 999999;
		for (int i = enemies.length; i-- > 0;) {
			RobotInfo info = enemies[i];
			if (info.type != RobotType.SOLDIER) continue;
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

	// Assumes attackableEnemies contains a pastr or noise tower
	private void attackABuilding() throws GameActionException {
		RobotInfo target = chooseBuildingAttackTarget();
		attackAndRecord(target);
	}

	// If we can only attack buildings, this function decides which one to attack.
	// It assumes that the enemies list does not contain any SOLDIERs or HQs!
	private RobotInfo chooseBuildingAttackTarget() throws GameActionException {
		RobotInfo ret = null;
		double bestHealth = 999999;
		RobotType bestType = RobotType.NOISETOWER;
		for (int i = attackableEnemies.length; i-- > 0;) {
			RobotInfo info = attackableEnemies[i];
			RobotType type = info.type;
			if (type == bestType) { // break ties between equal types by health
				double health = info.health;
				if (health < bestHealth) {
					bestHealth = health;
					ret = info;
				}
			} else if (type == RobotType.PASTR && bestType == RobotType.NOISETOWER) {
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
			fireOneLastShot &= attackableEnemies[i].actionDelay >= 3 || attackableEnemies[i].constructingRounds > 0;
			if (!fireOneLastShot) break;
		}

		if (fireOneLastShot) {
			// Debug.indicate("micro", 2, "parthian shot");
			attackASoldier();
			return;
		}

		Direction dir = chooseRetreatDirection();
		if (dir == null) { // Can't retreat! Fight!
			// Debug.indicate("micro", 2, "couldn't retreat; fighting instead");
			RobotInfo target = chooseSoldierAttackTarget(attackableEnemies);
			attackAndRecord(target);
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
		int[] numEnemiesAttackingDir = countNumEnemiesAttackingMoveDirs();

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
		int[] tryDirs = new int[] { 0, 1, -1, 2, -2, 3, -3, 4 };
		for (int i = 0; i < tryDirs.length; i++) {
			Direction tryDir = Direction.values()[(retreatDir.ordinal() + tryDirs[i] + 8) % 8];
			if (!rc.canMove(tryDir)) continue;
			MapLocation tryLoc = here.add(tryDir);
			if (numEnemiesAttackingDir[tryDir.ordinal()] > 0) continue;
			if (Util.inHQAttackRange(tryLoc, theirHQ)) continue;
			return tryDir;
		}

		return null;
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
