package noisy;

import battlecode.common.*;

public class BotNoiseTower extends Bot {
	public BotNoiseTower(RobotController theRC) {
		super(theRC);
	}

	public void turn() throws GameActionException {
		herdInward();
	}

	double angle = 0;
	double radius = 9;
	double rate = 2.0/9.0;
	
	public void herdInward() throws GameActionException {
		
		radius = 20 - Math.min(13, (Clock.getRoundNum() % 120)*16.0/120.0);
		
		MapLocation target = new MapLocation((int)(here.x + radius*Math.cos(angle)), (int)(here.y + radius*Math.sin(angle)));
		angle += rate;
		rc.setIndicatorString(0, "angle = " + angle);
		if(rc.canAttackSquare(target)) {
			rc.attackSquare(target);
		}
	}
}
