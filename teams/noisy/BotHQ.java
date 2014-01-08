package noisy;

import battlecode.common.*;

public class BotHQ extends Bot {
	public BotHQ(RobotController theRC) {
		super(theRC);
	}

	int maxBots = 0;
	int maxSpawnDelay = 0;
	
	public void turn() throws GameActionException {
        maxSpawnDelay--;
        if(maxSpawnDelay <= 0) {
            maxBots++;
            maxSpawnDelay = (int)(GameConstants.HQ_SPAWN_DELAY_CONSTANT_1 + Math.pow(maxBots, GameConstants.HQ_SPAWN_DELAY_CONSTANT_2));
        }
        rc.setIndicatorString(0, String.format("turn %d: maxBots = %d; spawn delay = %d", Clock.getRoundNum(), maxBots, maxSpawnDelay));
        
        if (!rc.isActive()) return;
		
		for(RobotType type : RobotType.values()) {
		    System.out.format("%s: sense range^2 = %d; attack range^2 = %d\n", type.toString(), type.sensorRadiusSquared, type.attackRadiusMaxSquared);
		}
        
		if (attackEnemies()) return;
		spawnSoldier();
	}

	boolean attackEnemies() throws GameActionException {
		Robot[] enemies = rc.senseNearbyGameObjects(Robot.class, RobotType.HQ.attackRadiusMaxSquared, them);
		if (enemies.length == 0) return false;

		RobotInfo info = rc.senseRobotInfo(enemies[0]);
		MapLocation target = info.location;
		rc.attackSquare(target);
		return true;
	}

	boolean spawnSoldier() throws GameActionException {
		if (rc.senseRobotCount() >= GameConstants.MAX_ROBOTS) return false;

		Direction dir = ourHQ.directionTo(theirHQ);
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
