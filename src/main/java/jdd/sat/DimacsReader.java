
package jdd.sat;

import java.io.*;
import java.util.*;


public class DimacsReader {
	private BufferedReader br;
	private CNF ret = null;

	public DimacsReader(String str, boolean isFile) throws IOException {
		Reader rd = null;
		if(isFile) rd = new FileReader(str);
		else       rd = new StringReader(str);

		br = new BufferedReader ( rd);

		StringTokenizer tmp = getLine();

		if(tmp == null) throw new IOException("Load failed, empty formula?? ");

		assure(tmp, "p");
		assure(tmp, "cnf");

		int n_lits = getInt(tmp);
		int n_cls  = getInt(tmp);

		ret = new CNF(n_cls, n_lits);


		int [] buff = new int[n_lits + 1];

		while( (tmp = getLine()) != null) {
			int lit, buff_ptr = 0;
			while((lit = getInt(tmp)) != 0 )
				buff[buff_ptr++] = lit;

			Clause c = new Clause(buff_ptr);
			for(int i = 0; i < buff_ptr; i++) {
				Lit x = ret.getSignedLit( buff[i] );
				if(x == null) throw new IOException("BAD literal; " + buff[i]);
				if(x.index > n_lits) throw new IOException("max literals " + n_cls + " but read " + buff[i]);
				c.insert( x );
			}

			ret.insert(c);
		}

		ret.adjustNumClauses();

		br.close();
	}

	public CNF getFormula(){ return ret; }

	/** assert next word in the line is the one we want */
	private void assure(StringTokenizer st, String str) throws IOException {
		if(!st.hasMoreTokens()) throw new IOException("pre-mature end of line when waiting for " + str);
		String got = st.nextToken();
		if(! got.equalsIgnoreCase( str) ) throw new IOException("Expected " + str + ", got " + got);
	}

	/** get the next integer in the line */
	private int getInt(StringTokenizer st) throws IOException {
		if(!st.hasMoreTokens()) throw new IOException("pre-mature end of line when waiting for a number ");
		return Integer.parseInt( st.nextToken() );
	}

	/** get next line, ignore commens (comments start with 'c', doesn't this remainds you of something?) */
	private StringTokenizer getLine() throws IOException	{
		String line;
		for(;;) {
			line = br.readLine();
			if(line == null) return null;
			if(line.length() > 0 && line.charAt(0) != 'c') return new StringTokenizer(line);
		}
	}
}
