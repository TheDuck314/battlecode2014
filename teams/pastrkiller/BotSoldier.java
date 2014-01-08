package pastrkiller;

import battlecode.common.*;

public class BotSoldier extends Bot {
	public BotSoldier(RobotController theRC) {
		super(theRC);
		Debug.init(theRC, "pastr");
		Nav.init(theRC);

		chooseInitialState();
	}

	private enum SoldierState {
		RALLYING, ATTACKING
	}

	SoldierState state;

	// MOVING_TO_PASTR_LOC
	MapLocation rallyPoint;

	public void turn() throws GameActionException {
		if (!rc.isActive()) return;

		Debug.indicate("pastr", 0, "state = " + state.toString());

		if(fight()) return;
		
		switch (state) {
			case RALLYING:
				rally();
				break;

			case ATTACKING:
				attack();
				break;
		}
	}

	private void chooseInitialState() {
		state = SoldierState.RALLYING;
		rallyPoint = new MapLocation(rc.getMapWidth() / 2, rc.getMapHeight() / 2);
	}

	private void rally() throws GameActionException {
		Nav.goTo(rallyPoint);

		if (Clock.getRoundNum() > 150) {
			MapLocation[] theirPastrs = rc.sensePastrLocations(them);
			if (theirPastrs.length > 0) {
				state = SoldierState.ATTACKING;
			}
		}
	}

	private void attack() throws GameActionException {
		MapLocation[] theirPastrs = rc.sensePastrLocations(them);
		if (theirPastrs.length == 0) {
			state = SoldierState.RALLYING;
			return;
		}

		MapLocation closestTarget = Util.closest(here, theirPastrs);
		Nav.goTo(closestTarget);
	}

	private boolean fight() throws GameActionException {
		Robot[] enemies = rc.senseNearbyGameObjects(Robot.class, RobotType.SOLDIER.attackRadiusMaxSquared, them);

		if (enemies.length == 0) { return false; }

		MapLocation attackTarget = chooseAttackTarget(enemies);

		if (attackTarget == null) { return false; }

		rc.attackSquare(attackTarget);
		return true;
	}

	private MapLocation chooseAttackTarget(Robot[] enemies) throws GameActionException {
		MapLocation ret = null;
		double bestHealth = 999999;
		int bestOrdinal = 1; // Ranking by greater ordinal gives SOLDIER > PASTR > NOISETOWER, as desired
		for (int i = enemies.length; i-- > 0;) {
			RobotInfo info = rc.senseRobotInfo(enemies[i]);
			int ordinal = info.type.ordinal();
			if (ordinal >= bestOrdinal) {
				double health = info.health;
				if (health < bestHealth || ordinal > bestOrdinal) {
					bestHealth = health;
					bestOrdinal = ordinal;
					ret = info.location;
				}
			}
		}
		return ret;
	}
}
