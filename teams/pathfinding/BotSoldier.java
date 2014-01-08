package pathfinding;

import battlecode.common.*;

public class BotSoldier extends Bot {
	public BotSoldier(RobotController theRC) {
		super(theRC);
		Debug.init(theRC, "bug");
		Nav.init(theRC);
	}

	public void turn() throws GameActionException {
		if (!rc.isActive()) return;

		Nav.goTo(theirHQ);
	}
}
