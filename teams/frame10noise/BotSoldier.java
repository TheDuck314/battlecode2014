package frame10noise;

import java.util.ArrayList;

import battlecode.common.*;

public class BotSoldier extends Bot {
	public BotSoldier(RobotController theRC) {
		super(theRC);
		Debug.init(theRC, "micro");
		Nav.init(theRC);
	}

	public void turn() throws GameActionException {
		Debug.indicate("construct", 0, "construction timer = " + rc.getConstructingRounds());
		if (rc.getConstructingRounds() > 0) return; // can't do anything while constructing

		if (rc.isActive()) {
			if (fight()) return;
		}

		MapLocation attackTarget = MessageBoard.ATTACK_LOC.readMapLocation(rc);
		if (attackTarget != null) {
			Nav.goTo(attackTarget, Nav.Sneak.NO);
			return;
		}

		if (tryBuildNoiseTower()) return;

		MapLocation pastr = MessageBoard.BEST_PASTR_LOC.readMapLocation(rc);
		if (pastr != null) {
			if (here.equals(pastr)) {
				if (rc.isActive()) {
					rc.construct(RobotType.PASTR);
					return;
				}
			} else {
				MapLocation[] ourPastrs = rc.sensePastrLocations(us);
				Nav.Sneak sneak = ourPastrs.length > 0 && here.distanceSquaredTo(ourPastrs[0]) <= 30 ? Nav.Sneak.YES : Nav.Sneak.NO;
				Nav.goTo(pastr, sneak);
				return;
			}
		}
	}

	// After a little while, if nothing has gone wrong, if we are next to a pastr and no one
	// else is building or has built a noise tower, build one.
	private boolean tryBuildNoiseTower() throws GameActionException {
		if (!rc.isActive()) return false;
		if (Clock.getRoundNum() < 200) return false;
		if (rc.senseRobotCount() < 6) return false;

		// A problem with building a noise tower is that it makes us more vulnerable to
		// a rush. If it's early and the opponent hasn't build a pastr, let's hold off
		// in case it means that they are rushing.
		if (Clock.getRoundNum() < 300 && rc.sensePastrLocations(them).length == 0) return false;

		// Only allowed to build noise tower if adjacent to pastr
		MapLocation[] ourPastrs = rc.sensePastrLocations(us);
		if (ourPastrs.length == 0 || !here.isAdjacentTo(ourPastrs[0])) return false;

		// Check if someone else is already building a noise tower
		MapLocation existingBuilder = MessageBoard.BUILDING_NOISE_TOWER.readMapLocation(rc);
		if (existingBuilder != null) {
			if (rc.senseNearbyGameObjects(Robot.class, existingBuilder, 1, us).length > 0) {
				return false;
			}
		}

		// Construct the noise tower and advertise the fact that we are doing it
		rc.construct(RobotType.NOISETOWER);
		MessageBoard.BUILDING_NOISE_TOWER.writeMapLocation(here, rc);
		return true;
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

	private boolean fight() throws GameActionException {
		Robot[] visibleEnemyRobots = rc.senseNearbyGameObjects(Robot.class, RobotType.SOLDIER.sensorRadiusSquared, them);
		if (visibleEnemyRobots.length == 0) return false; // TODO: sense nearby battles with messages.

		RobotInfo[] visibleEnemies = new RobotInfo[visibleEnemyRobots.length];
		RobotInfo[] attackableEnemies = processVisibleEnemies(visibleEnemyRobots, visibleEnemies);
		Debug.indicate("micro", 0, String.format("numVisibleEnemies = %d; numAttackableEnemies = %d", visibleEnemies.length, attackableEnemies.length));

		if (attackableEnemies.length != 0) { // There is at least one enemy in attack range (which should hopefully not be their HQ)
			int numAttackableSoldiers = Util.countSoldiers(attackableEnemies);
			if (numAttackableSoldiers >= 1) {
				// Decide whether to attack, retreat, or reposition
				if (numAttackableSoldiers >= 2) { // We're getting double-teamed!!
					// retreat unless one of our attackers is also getting double-teamed just as bad
					boolean anEnemyIsDoubleTeamed = false;
					for (int i = attackableEnemies.length; i-- > 0;) {
						if (attackableEnemies[i].type != RobotType.SOLDIER) continue; // don't care if non-soldier enemies are double-teamed
						MapLocation enemyLoc = attackableEnemies[i].location;
						anEnemyIsDoubleTeamed |= numOtherAlliesInAttackRange(enemyLoc) >= 1;
						if (anEnemyIsDoubleTeamed) break;
					}
					if (anEnemyIsDoubleTeamed) { // Fight!
						Debug.indicate("micro", 1, "double-teamed, but so is an enemy: fighting");
						attackASoldier(attackableEnemies);
						return true;
					} else { // no enemy is double-teamed. Retreat!
						Debug.indicate("micro", 1, "double-teamed: retreating");
						retreatOrFight(visibleEnemies, attackableEnemies);
						return true;
					}
				} else { // We're not getting double-teamed. We're within attack range of exactly one soldier
					// Fight on if we are winning the 1v1 or if the other guy is double-teamed. Otherwise retreat
					// First find the single enemy
					RobotInfo enemySoldier = findASoldier(attackableEnemies);
					if (enemySoldier.health <= rc.getHealth() || numOtherAlliesInAttackRange(enemySoldier.location) >= 1) {
						// Kill him!
						Debug.indicate("micro", 1, "winning vs single enemy: fighting");
						rc.attackSquare(enemySoldier.location);
						return true;
					} else {
						// retreat!
						Debug.indicate("micro", 1, "losing vs single enemy: retreating (numOtherAllies in range of " + enemySoldier.location.toString()
								+ ") is " + numOtherAlliesInAttackRange(enemySoldier.location));
						retreatOrFight(visibleEnemies, attackableEnemies);
						return true;
					}
				}
			} else { // can't attack a soldier
				boolean canSeeSoldier = Util.countSoldiers(visibleEnemies) > 0;
				if (canSeeSoldier) {
					// handle the case when we can attack a building, but can see a soldier.
					// Need to decide whether to move to attack a soldier or attack the building
					// For the moment, let's just attack a building.
					// TODO: add some code that makes us move to support allies when appropriate
					Debug.indicate("micro", 1, "can see a soldier, but preferring to attack a building");
					attackABuilding(attackableEnemies);
					return true;
				} else {
					// Can't see any soldiers, but can attack a building. Do it
					Debug.indicate("micro", 1, "can't see a soldier; attacking a building");
					attackABuilding(attackableEnemies);
					return true;
				}
			}
		} else { // No attackable enemies! But there is at least one visible enemy
			boolean canSeeSoldier = Util.countSoldiers(visibleEnemies) > 0;
			if (canSeeSoldier) {
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
				// TODO: we need not be afraid of robots with constructingRounds > 0 (though this is a rare case)
				int[] numEnemiesAttackingDirs = countNumEnemiesAttackingMoveDirs(visibleEnemies);

				MapLocation closestSoldier = Util.closestSoldier(visibleEnemies, rc);
				Direction toClosest = here.directionTo(closestSoldier);
				Direction[] tryDirs = new Direction[] { toClosest, toClosest.rotateLeft(), toClosest.rotateRight() };
				for (int i = 0; i < tryDirs.length; i++) {
					Direction tryDir = tryDirs[i];
					if (!rc.canMove(tryDir)) continue;
					if (numEnemiesAttackingDirs[tryDir.ordinal()] > 0) continue;
					if (isNearTheirHQ(here.add(tryDir))) continue;
					Debug.indicate("micro", 1, String.format("cautiously approaching enemy soldier; direction %d; attackers = %d %d %d %d %d %d %d %d",
							tryDir.ordinal(), numEnemiesAttackingDirs[0], numEnemiesAttackingDirs[1], numEnemiesAttackingDirs[2], numEnemiesAttackingDirs[3],
							numEnemiesAttackingDirs[4], numEnemiesAttackingDirs[5], numEnemiesAttackingDirs[6], numEnemiesAttackingDirs[7]));
					rc.move(tryDir);
					return true;
				}
				Debug.indicate("micro", 1, "can't safely approach enemy soldier");
				return true;
			} else { // Can't see a soldier, only buildings
				// Make sure we aren't just seeing the HQ
				boolean canSeeBuilding = visibleEnemies.length > 2 || visibleEnemies[0].type != RobotType.HQ;
				if (canSeeBuilding) { // can see a non-HQ building
					// Move toward the building, but only if this is really easy. I'm worried about getting
					// stuck when we pass a building on the other side of a wall. Even this code might still
					// get stuck in certain situations. Note that if we are navigating to the building, we'll
					// keep doing so if we return false so this just lets us ignore buildings we aren't going for.
					MapLocation closestBuilding = Util.closestNonHQ(visibleEnemies, rc);
					Direction dir = here.directionTo(closestBuilding);
					if (rc.canMove(dir) && !isNearTheirHQ(here.add(dir))) {
						Debug.indicate("micro", 1, "moving towards visible building");
						rc.move(dir);
						return true;
					} else { // There's something in the way. Don't go after the building
						Debug.indicate("micro", 1, "ignoring visible building because we can't go straight at it");
						return false;
					}
				} else { // can only see the enemy HQ. we are not really fighting
					Debug.indicate("micro", 1, "can only see enemy HQ");
					return false;
				}
			}
		}
	}

	private RobotInfo[] processVisibleEnemies(Robot[] visibleEnemyRobots, RobotInfo[] visibleEnemies) throws GameActionException {
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
		RobotInfo[] attackableEnemies = new RobotInfo[numAttackableEnemies];
		int enemyCounter = 0;
		for (int i = visibleEnemies.length; i-- > 0;) {
			if (attackable[i]) attackableEnemies[enemyCounter++] = visibleEnemies[i];
		}
		return attackableEnemies;
	}

	// TODO: this function counts buildings as allies :( Consider fixing this
	// This function costs at least 100 bytecodes!
	private int numOtherAlliesInAttackRange(MapLocation loc) {
		return rc.senseNearbyGameObjects(Robot.class, loc, RobotType.SOLDIER.attackRadiusMaxSquared, us).length;
	}

	// TODO: this function counts buildings as enemies :( Consider fixing this
	// This function costs at least 100 bytecodes!
	private int numEnemiesInAttackRange(MapLocation loc) {
		return rc.senseNearbyGameObjects(Robot.class, loc, RobotType.SOLDIER.attackRadiusMaxSquared, them).length;
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
	private void attackASoldier(RobotInfo[] attackableEnemies) throws GameActionException {
		MapLocation target = chooseSoldierAttackTarget(attackableEnemies);
		rc.attackSquare(target);
	}

	// Assumes enemies list contains at least one soldier!
	// TODO: focus on robots with constructingRounds == 0? This seems like a rare case and maybe not worth it
	private MapLocation chooseSoldierAttackTarget(RobotInfo[] enemies) throws GameActionException {
		MapLocation ret = null;
		double bestNumNearbyAllies = -1;
		double bestHealth = 999999;
		double bestActionDelay = 999999;
		for (int i = enemies.length; i-- > 0;) {
			RobotInfo info = enemies[i];
			if (info.type != RobotType.SOLDIER) continue;
			MapLocation enemyLoc = info.location;
			int numNearbyAllies = numOtherAlliesInAttackRange(enemyLoc);
			if (numNearbyAllies > bestNumNearbyAllies) {
				bestNumNearbyAllies = numNearbyAllies;
				bestHealth = info.health;
				bestActionDelay = info.actionDelay;
				ret = enemyLoc;
			} else if (numNearbyAllies == bestNumNearbyAllies) {
				double health = info.health;
				if (health < bestHealth) {
					bestHealth = health;
					bestActionDelay = info.actionDelay;
					ret = enemyLoc;
				} else if (health == bestHealth) {
					double actionDelay = info.actionDelay;
					if (actionDelay < bestActionDelay) {
						bestActionDelay = actionDelay;
						ret = enemyLoc;
					}
				}
			}
		}
		return ret;
	}

	// Assumes attackableEnemies contains a pastr or noise tower
	private void attackABuilding(RobotInfo[] attackableEnemies) throws GameActionException {
		MapLocation target = chooseBuildingAttackTarget(attackableEnemies);
		rc.attackSquare(target);
	}

	// If we can only attack buildings, this function decides which one to attack.
	// It assumes that the enemies list does not contain any SOLDIERs or HQs!
	private MapLocation chooseBuildingAttackTarget(RobotInfo[] enemies) throws GameActionException {
		MapLocation ret = null;
		double bestHealth = 999999;
		RobotType bestType = RobotType.NOISETOWER;
		for (int i = enemies.length; i-- > 0;) {
			RobotInfo info = enemies[i];
			RobotType type = info.type;
			if (type == bestType) { // break ties between equal types by health
				double health = info.health;
				if (health < bestHealth) {
					bestHealth = health;
					ret = info.location;
				}
			} else if (type == RobotType.PASTR && bestType == RobotType.NOISETOWER) {
				bestType = type;
				bestHealth = info.health;
				ret = info.location;
			}
		}
		return ret;
	}

	private void retreatOrFight(RobotInfo[] visibleEnemies, RobotInfo[] attackableEnemies) throws GameActionException {
		Direction dir = chooseRetreatDirection(visibleEnemies);
		if (dir == null) { // Can't retreat! Fight!
			Debug.indicate("micro", 2, "couldn't retreat; fighting instead");
			MapLocation target = chooseSoldierAttackTarget(attackableEnemies);
			rc.attackSquare(target);
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

	private int[] countNumEnemiesAttackingMoveDirs(RobotInfo[] visibleEnemies) {
		int[] numEnemiesAttackingDir = new int[8];
		for (int i = visibleEnemies.length; i-- > 0;) {
			RobotInfo info = visibleEnemies[i];
			if (info.type == RobotType.SOLDIER) {
				MapLocation enemyLoc = visibleEnemies[i].location;
				int[] attackedDirs = attackNotes[5 + enemyLoc.x - here.x][5 + enemyLoc.y - here.y];
				for (int j = attackedDirs.length; j-- > 0;) {
					numEnemiesAttackingDir[attackedDirs[j]]++;
				}
			}
		}
		return numEnemiesAttackingDir;
	}

	private Direction chooseRetreatDirection(RobotInfo[] visibleEnemies) throws GameActionException {
		int[] numEnemiesAttackingDir = countNumEnemiesAttackingMoveDirs(visibleEnemies);

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
			if (isNearTheirHQ(tryLoc)) continue;
			return tryDir;
		}

		return null;
	}

	// A useful function since under no circumstances do we want to walk on squares that are attackable by the enemy HQ.
	// Currently it incorrectly excludes the delta of (5, 0), which the HQ can't actually reach with splash damage, but
	// hopefully we don't ever actually need to go to that square
	private boolean isNearTheirHQ(MapLocation loc) {
		return theirHQ.distanceSquaredTo(loc) <= 25;
	}
}
