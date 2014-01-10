package framework;

import battlecode.common.*;

public class BotSoldier extends Bot {
	public BotSoldier(RobotController theRC) {
		super(theRC);
		Debug.init(theRC, "path");
		Nav.init(theRC);
	}

	public void turn() throws GameActionException {
		if (rc.isActive()) {
			if (fight()) return;
		}

		// do stuff
	}

	private boolean fight() throws GameActionException {
		// If possible, attack an enemy
		Robot[] attackableEnemies = rc.senseNearbyGameObjects(Robot.class, RobotType.SOLDIER.attackRadiusMaxSquared, them);
		if (attackableEnemies.length > 0) {
			MapLocation attackTarget = chooseAttackTarget(attackableEnemies);
			if (attackTarget != null) {
				Debug.indicate("attack", 1, "attacking enemy at " + attackTarget.toString());
				rc.attackSquare(attackTarget);
				return true;
			}
		}

		// Otherwise, if there is a visible enemy, move towards an enemy
		Robot[] visibleEnemies = rc.senseNearbyGameObjects(Robot.class, RobotType.SOLDIER.sensorRadiusSquared, them);
		if (visibleEnemies.length > 0) {
			MapLocation approachTarget = chooseApproachTarget(visibleEnemies);
			if (approachTarget != null) {
				Debug.indicate("attack", 1, "approaching enemy at " + approachTarget.toString());
				Nav.goTo(approachTarget);
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
