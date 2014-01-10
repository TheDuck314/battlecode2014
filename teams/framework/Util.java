package framework;

import battlecode.common.*;

public class Util {
	public static boolean passable(TerrainTile t) {
		return t == TerrainTile.NORMAL || t == TerrainTile.ROAD;
	}

	public static MapLocation closest(MapLocation[] locs, MapLocation to) {
		MapLocation ret = null;
		int bestDistSq = 999999;
		for (int i = locs.length; i-- > 0;) {
			int distSq = locs[i].distanceSquaredTo(to);
			if (distSq < bestDistSq) {
				bestDistSq = distSq;
				ret = locs[i];
			}
		}
		return ret;
	}
	
	public static boolean contains(MapLocation[] locs, MapLocation x) {
		for(int i = locs.length; i --> 0; ) {
			if(x.equals(locs[i])) return true;
		}
		return false;
	}

	public static Direction opposite(Direction dir) {
		return Direction.values()[(dir.ordinal() + 4) % 8];
	}

	public static TerrainTile[][] makeTerrainCache(RobotController rc) {
		int mapWidth = rc.getMapWidth();
		int mapHeight = rc.getMapHeight();
		TerrainTile[][] cache = new TerrainTile[mapWidth][mapHeight];
		for (int x = mapWidth; x-- > 0;) {
			for (int y = mapHeight; y-- > 0;) {
				cache[x][y] = rc.senseTerrainTile(new MapLocation(x, y));
			}
		}
		return cache;
	}
	
	public static void debugBytecodes(String message) {
		System.out.format("turn: %d, bytecodes: %d: %s\n", Clock.getRoundNum(), Clock.getBytecodeNum(), message);
	}

}
