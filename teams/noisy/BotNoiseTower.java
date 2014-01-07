package noisy;

import battlecode.common.*;

public class BotNoiseTower extends Bot {
	public BotNoiseTower(RobotController theRC) {
		super(theRC);
	}

	public void turn() throws GameActionException {
		herdInward();
	}

	Direction herdFromDir = null;
	
	public void herdInward() throws GameActionException {
		if(herdFromDir == null) {
			herdFromDir = Direction.NORTH;
		}
		if(Clock.getRoundNum() % 20 == 0) {
			herdFromDir = herdFromDir.rotateRight();
		}
		
		MapLocation target = here.add(herdFromDir, 20 - (Clock.getRoundNum() % 20));
		if(rc.canAttackSquare(target)) {
			rc.attackSquare(target);
		}
	}
}
