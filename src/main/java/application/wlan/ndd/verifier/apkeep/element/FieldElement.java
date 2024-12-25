package application.wlan.ndd.verifier.apkeep.element;

import java.util.HashMap;
import java.util.HashSet;

import javafx.util.*;

public class FieldElement {
    public int field;
    public boolean is_end;
    public String port;
    public HashMap<FieldElement, Integer> port_pred;
    public HashMap<FieldElement, HashSet<Integer>> port_aps;
    public FieldElement(Integer field)
    {
        this.field = field;
        port_pred = new HashMap<FieldElement, Integer>();
        port_aps = new HashMap<FieldElement, HashSet<Integer>>();
        is_end = false;
    }
    public FieldElement(int field, HashMap<FieldElement, Integer> port_pred)
    {
        this.field = field;
        this.port_pred = port_pred;
        port_aps = new HashMap<FieldElement, HashSet<Integer>>();
        is_end = false;
    }
    public FieldElement(String port)
    {
        is_end = true;
        this.port = port;
    }

    public boolean equals(FieldElement obj)
	{
        if(this.is_end != obj.is_end)
        {
            return false;
        }
        else
        {
            if(this.is_end)
            {
                return this.port.equalsIgnoreCase(obj.port);
            }
            else
            {
                return (this.field == obj.field) && (this.port_pred.equals(obj.port_pred));
            }
        }
	}
}