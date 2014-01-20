package anatid;

import battlecode.common.*;

public class BotPastr extends Bot {
	public BotPastr(RobotController theRC) throws GameActionException {
		super(theRC);
		Debug.init(theRC, "pages");
		
		//claim the assignment to build this pastr so others know not to build it
		int numPastrLocations = MessageBoard.NUM_PASTR_LOCATIONS.readInt();
		for(int i = 0; i < numPastrLocations; i++) {
			MapLocation pastrLoc = MessageBoard.BEST_PASTR_LOCATIONS.readFromMapLocationList(i);
			if(rc.getLocation().equals(pastrLoc)) {
				MessageBoard.PASTR_BUILDER_ROBOT_IDS.claimAssignment(i);
				break;
			}
		}
	}

	public void turn() throws GameActionException {
		// do speculative pathing calculations
		int bytecodeLimit = 9000;
		MapLocation[] enemyPastrs = rc.sensePastrLocations(them);
		for (int i = 0; i < enemyPastrs.length; i++) {
			if (Clock.getBytecodeNum() > bytecodeLimit) break;
			Bfs.work(enemyPastrs[i], Bfs.PRIORITY_LOW, bytecodeLimit);
		}
	}

}
