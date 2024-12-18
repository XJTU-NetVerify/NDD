
package jdd.util;


/**
 * This is a FIFO queue, implemented as a ring.
 * Given the maximum number of elements that might be in the queue at any time,
 * RingQueue creates a fast data structure, but it wont check for queue-overflow!!
 */

public class RingQueue {
	private int size, read, write;
	private Object [] data;

	public RingQueue (int size) {
		size++;
		this.size = size;
		this.data = new Object[size];
		reset();
	}

	/** start over, empties the queue */
	public void reset() {
		read = write = 0;
	}

	/** are there any elements in the queue ?*/
	public boolean empty() {
		return (read == write);
	}

	/** put an object in the queue */
	public void enqueue(Object v) {
		data[write] = v;
		write = (write +1) % size;
	}

	/**
	 * get the first object in the queue. returns 'null' if the queue is empty
	 */
	public Object dequeue() {
		if(empty() ) return null;
		Object tmp = data[read];
		read = (read +1) % size;
		return tmp;
	}
}
