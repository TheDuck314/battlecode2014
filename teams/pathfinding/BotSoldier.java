package pathfinding;

import battlecode.common.*;

public class BotSoldier extends Bot {
	public BotSoldier(RobotController theRC) {
		super(theRC);
		Nav.init(theRC);
	}

	public void turn() throws GameActionException {
		if (!rc.isActive()) return;

		Nav.goTo(theirHQ);
	}
}
