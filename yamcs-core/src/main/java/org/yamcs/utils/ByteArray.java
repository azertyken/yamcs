package org.yamcs.utils;

import java.util.Arrays;

/**
 * byte array
 * 
 * 
 * @author nm
 *
 */
public class ByteArray {
    public static int DEFAULT_CAPACITY = 10;
    private byte[] a;
    private int length;

    // caches the hashCode
    private int hash;

    /**
     * Creates a sorted int array with a default initial capacity
     */
    public ByteArray() {
        a = new byte[DEFAULT_CAPACITY];
    }

    /**
     * Creates an IntArray with a given initial capacity
     * 
     * @param capacity
     */
    public ByteArray(int capacity) {
        a = new byte[capacity];
    }

    private ByteArray(byte[] a1) {
        a = a1;
        length = a1.length;
    }

    /**
     * Creates the IntArray with the backing array
     * 
     * @param array
     * @return a new object containing all the values from the passed array
     */
    public static ByteArray wrap(byte... array) {
        return new ByteArray(array);
    }

    /**
     * add value to the array
     * 
     * @param x
     *            - value to be added
     */
    public void add(byte x) {
        ensureCapacity(length + 1);
        a[length] = x;
        length++;
    }

    public void add(int pos, byte x) {
        if (pos > length) {
            throw new IndexOutOfBoundsException("Index: " + pos + " length: " + length);
        }
        ensureCapacity(length + 1);
        System.arraycopy(a, pos, a, pos + 1, length - pos);
        a[pos] = x;
        length++;
    }

    /**
     * get element at position
     * 
     * @param pos
     * @return the element at the specified position
     */
    public byte get(int pos) {
        rangeCheck(pos);

        return a[pos];
    }

    private void ensureCapacity(int minCapacity) {
        if (minCapacity <= a.length) {
            return;
        }

        int capacity = a.length;
        int newCapacity = capacity + (capacity >> 1);
        if (newCapacity < minCapacity) {
            newCapacity = minCapacity;
        }

        a = Arrays.copyOf(a, newCapacity);
    }

    public boolean isEmpty() {
        return a.length == 0;
    }

    public byte[] toArray() {
        return Arrays.copyOf(a, length);
    }

    public int size() {
        return length;
    }

    public void set(int pos, byte x) {
        rangeCheck(pos);
        a[pos] = x;
    }

    private void rangeCheck(int pos) {
        if (pos >= length) {
            throw new IndexOutOfBoundsException("Index: " + pos + " length: " + length);
        }
    }

    /**
     * Returns the index of the first occurrence of the specified element in the
     * array, or -1 if the array does not contain the element.
     * 
     * @param x
     *            element which is searched for
     * 
     * @return the index of the first occurrence of the specified element in
     *         this list, or -1 if this list does not contain the element.
     */
    public int indexOf(byte x) {
        for (int i = 0; i < length; i++) {
            if (a[i] == x)
                return i;
        }
        return -1;
    }

   

    @Override
    public int hashCode() {
        int h = hash;
        if (h == 0 && length > 0) {
            h = 1;

            for (int i = 0; i < length; i++) {
                h = 31 * h + a[i];
            }
            hash = h;
        }
        return h;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj)
            return true;

        if (obj == null)
            return false;

        if (getClass() != obj.getClass())
            return false;

        ByteArray other = (ByteArray) obj;
        if (length != other.length)
            return false;

        for (int i = 0; i < length; i++) {
            if (a[i] != other.a[i])
                return false;
        }

        return true;
    }

    /**
     * get the backing array
     * 
     * @return the backing array
     */
    public byte[] array() {
        return a;
    }
    public String toString() {
        StringBuilder b = new StringBuilder();
        int n = length - 1;
        if(n==-1) {
            return "[]";
        }
        b.append('[');
        for (int i = 0;; i++) {
            b.append(a[i]);
            if (i == n)
                return b.append(']').toString();
            b.append(", ");
        }
    }
}
