package anatid16_shotgun;

import battlecode.common.*;

public class BotNoiseTower extends Bot {
	public BotNoiseTower(RobotController theRC) throws GameActionException {
		super(theRC);
		Debug.init(theRC, "pages");
	}

	MapLocation pastr = null;
	boolean finishedDumbHerding = false;

	public void turn() throws GameActionException {
		if (!rc.isActive()) return;

		MapLocation nearestPastr = findNearestAlliedPastr();
		if (nearestPastr != pastr) {
			pastr = nearestPastr;
		}
		if (pastr == null) pastr = here;

		//if (!finishedDumbHerding) herdTowardPastrDumb();
		//if (finishedDumbHerding) herdTowardPastrSmart();		
		herdTowardPastrDumb();
	}

	private MapLocation findNearestAlliedPastr() {
		MapLocation[] pastrs = rc.sensePastrLocations(us);
		return Util.closest(pastrs, here);
	}

	int radius = 20;
	Direction attackDir = Direction.NORTH;

	private void herdTowardPastrDumb() throws GameActionException {
		MapLocation target = null;

		do {
			int dx = radius * attackDir.dx;
			int dy = radius * attackDir.dy;
			if (attackDir.isDiagonal()) {
				dx = (int) (dx / 1.4);
				dy = (int) (dy / 1.4);
			}
			target = new MapLocation(pastr.x + dx, pastr.y + dy);
			attackDir = attackDir.rotateRight();
			if (attackDir == Direction.NORTH) radius--;
			if (radius <= 5) {
				finishedDumbHerding = true;
				radius = 20;
				return;
			}
		} while (tooFarOffMap(target) || !rc.canAttackSquare(target));

		rc.attackSquare(target);
	}

	boolean tooFarOffMap(MapLocation loc) {
		int W = 2;
		return loc.x < -W || loc.y < -W || loc.x >= rc.getMapWidth() + W || loc.y >= rc.getMapWidth() + W;
	}

	int herdTargetIndex = 0;
	int smartHerdMaxPoints = 400;

	void herdTowardPastrSmart() throws GameActionException {
		MapLocation attackTarget;
		int numHerdPoints = HerdPattern.readNumHerdPoints(rc);
		do {
			if (herdTargetIndex <= 0) {
				herdTargetIndex = Math.min(smartHerdMaxPoints, numHerdPoints - 1);
				smartHerdMaxPoints += 100;
			}

			// MapLocation herdTarget = herdPoints[herdTargetIndex];
			// Direction pointHerdDir = squareHerdDirs[herdTarget.x][herdTarget.y];
			MapLocation herdTarget = HerdPattern.readHerdMapLocation(herdTargetIndex, rc);
			Direction pointHerdDir = HerdPattern.readHerdDir(herdTargetIndex, rc);

			// attackTarget = herdTarget.add(Util.opposite(pointHerdDir), pointHerdDir.isDiagonal() ? 1 : 2);
			attackTarget = herdTarget.add(Util.opposite(pointHerdDir), pointHerdDir.isDiagonal() ? 2 : 3);

			int skip = herdTargetIndex < 100 ? FastRandom.randInt(1, 3) : FastRandom.randInt(1, 7);
			for (int i = skip; i-- > 0;) {
				herdTargetIndex--;
				MapLocation nextHerdTarget = HerdPattern.readHerdMapLocation(herdTargetIndex, rc);
				if (nextHerdTarget.x == 0 || nextHerdTarget.y == 0 || nextHerdTarget.x == rc.getMapWidth() - 1 || nextHerdTarget.y == rc.getMapHeight() - 1) break;
			}

			// if(herdTargetIndex < 50) herdTargetIndex -= FastRandom.randInt(1, 3);
			// else herdTargetIndex -= FastRandom.randInt(1, 7);
			// Debug.indicate("herd", 0, "" + herdTargetIndex);
			// herdTargetIndex -= 1 + (int)Math.sqrt(herdTargetIndex/2);
		} while (!rc.canAttackSquare(attackTarget));

		// rc.attackSquareLight(attackTarget);
		rc.attackSquare(attackTarget);
	}
}
