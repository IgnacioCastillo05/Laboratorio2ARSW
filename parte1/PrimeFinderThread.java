package edu.eci.arsw.primefinder;

import java.util.LinkedList;
import java.util.List;

public class PrimeFinderThread extends Thread {
    
    private int a, b;
    private List<Integer> primes;
    private final Object monitor;
    private final Control control;
    
    public PrimeFinderThread(int a, int b, Object monitor, Control control) {
        super();
        this.primes = new LinkedList<>();
        this.a = a;
        this.b = b;
        this.monitor = monitor;
        this.control = control;
    }
    
    @Override
    public void run() {
        for (int i = a; i < b; i++) {
            handlePausePoint();
            
            if (isPrime(i)) {
                primes.add(i);
                control.reportPrime();
                System.out.println(i);
            }
        }
    }
    
    private void handlePausePoint() {
        synchronized (monitor) {
            if (control.shouldPause()) {
                control.reportThreadPaused();
                
                while (control.shouldPause()) {
                    try {
                        monitor.wait();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        }
    }
    
    boolean isPrime(int n) {
        boolean ans;
        if (n > 2) { 
            ans = n % 2 != 0;
            for(int i = 3; ans && i*i <= n; i += 2) {
                ans = n % i != 0;
            }
        } else {
            ans = n == 2;
        }
        return ans;
    }
    
    public List<Integer> getPrimes() {
        return primes;
    }
}