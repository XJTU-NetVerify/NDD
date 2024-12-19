package jdd;

/**
 * The Version class contains the current version of the JDD library.
 *
 * If you plan to use the latest features in your programs, you should first check
 * with the build version here to ensure that jdd.jar is not outdated
 *
 */

public class Version {
	public static final int build = 104;
	public static final String date = "12 February 2012";

	public static void main(String[]_) {
		System.out.println("JDD build " + build);
	}
}

