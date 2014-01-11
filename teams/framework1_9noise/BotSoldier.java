package framework1_9noise;

import battlecode.common.*;

public class BotSoldier extends Bot {
	public BotSoldier(RobotController theRC) {
		super(theRC);
		Debug.init(theRC, "construct");
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

	private boolean fight() throws GameActionException {
		// If possible, attack an enemy
		Robot[] attackableEnemies = rc.senseNearbyGameObjects(Robot.class, RobotType.SOLDIER.attackRadiusMaxSquared, them);
		if (attackableEnemies.length > 0) {
			MapLocation attackTarget = chooseAttackTarget(attackableEnemies);
			if (attackTarget != null) {
				rc.attackSquare(attackTarget);
				return true;
			}
		}

		// Otherwise, if there is a visible enemy, move towards an enemy
		Robot[] visibleEnemies = rc.senseNearbyGameObjects(Robot.class, RobotType.SOLDIER.sensorRadiusSquared, them);
		if (visibleEnemies.length > 0) {
			MapLocation approachTarget = chooseApproachTarget(visibleEnemies);
			if (approachTarget != null) {
				Nav.goTo(approachTarget, Nav.Sneak.NO);
				return true;
			}
		}

		// Otherwise give up on fighting
		return false;
	}

	// Current attack strategy: prefer soldiers over pastrs over noise towers,
	// and within a type attack the guy with the lowest health, and break ties
	// between equal health by firing on the target with smaller actionDelay
	// TODO: test actiondealy tiebreaker
	private MapLocation chooseAttackTarget(Robot[] enemies) throws GameActionException {
		MapLocation ret = null;
		double bestHealth = 999999;
		double bestActionDelay = 999999;
		RobotType bestType = RobotType.HQ;
		for (int i = enemies.length; i-- > 0;) {
			RobotInfo info = rc.senseRobotInfo(enemies[i]);
			RobotType type = info.type;
			if (isBetterAttackTarget(type, bestType)) {
				double health = info.health;
				double actionDelay = info.actionDelay;
				if (type != bestType || health < bestHealth || (health == bestHealth && actionDelay < bestActionDelay)) {
					bestActionDelay = actionDelay;
					bestHealth = health;
					bestType = type;
					ret = info.location;
				}
			}
		}
		return ret;
	}

	// Whether type a is at least as good as type b for shooting at
	private boolean isBetterAttackTarget(RobotType a, RobotType b) {
		switch (a) {
			case SOLDIER:
				return true;

			case PASTR:
				return b != RobotType.SOLDIER;

			case NOISETOWER:
				return b != RobotType.SOLDIER && b != RobotType.PASTR;

			default:
				return false;
		}
	}

	// Current approach strategy: prefer soldiers over pastrs over noise towers,
	// and within a type attack the closest enemy, breaking ties by going for
	// the one with smaller health
	private MapLocation chooseApproachTarget(Robot[] enemies) throws GameActionException {
		MapLocation ret = null;
		double bestHealth = 999999;
		double bestDistSq = 999999;
		RobotType bestType = RobotType.HQ;
		for (int i = enemies.length; i-- > 0;) {
			RobotInfo info = rc.senseRobotInfo(enemies[i]);
			RobotType type = info.type;
			if (isBetterAttackTarget(type, bestType)) {
				if (type != RobotType.SOLDIER || isSafeToApproachSoldier(info.location)) {
					double health = info.health;
					double distSq = here.distanceSquaredTo(info.location);
					if (type != bestType || distSq < bestDistSq || (distSq == bestDistSq && health < bestHealth)) {
						bestHealth = health;
						bestDistSq = distSq;
						bestType = type;
						ret = info.location;
					}
				}
			}
		}
		return ret;
	}

	// Tells whether it's safe to walk into an enemy's attack range.
	// Currently we say it's safe if there is already an ally within the enemy's
	// attack range.
	private boolean isSafeToApproachSoldier(MapLocation loc) throws GameActionException {
		Robot[] allies = rc.senseNearbyGameObjects(Robot.class, loc, RobotType.SOLDIER.attackRadiusMaxSquared, us);
		if (allies.length == 0) return false;
		for (int i = allies.length; i-- > 0;) {
			RobotInfo info = rc.senseRobotInfo(allies[i]);
			if (info.type == RobotType.SOLDIER) return true;
		}
		return false;
	}
}
