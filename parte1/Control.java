/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package edu.eci.arsw.primefinder;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;

public class Control extends Thread {
    
    private final static int NTHREADS = 3;
    private final static int MAXVALUE = 30000000;
    private final static int TMILISECONDS = 5;
    private final int NDATA = MAXVALUE / NTHREADS;
    private PrimeFinderThread pft[];
    
    private final Object monitor = new Object();
    
    private enum State { RUNNING, PAUSED }
    private State currentState = State.RUNNING;
    private int primeCount = 0;
    private int threadsPaused = 0;
    
    private Control() {
        super();
        this.pft = new PrimeFinderThread[NTHREADS];
        int i;
        for(i = 0; i < NTHREADS - 1; i++) {
            PrimeFinderThread elem = new PrimeFinderThread(i*NDATA, (i+1)*NDATA, monitor, this);
            pft[i] = elem;
        }
        pft[i] = new PrimeFinderThread(i*NDATA, MAXVALUE + 1, monitor, this);
    }
    
    public static Control newControl() {
        return new Control();
    }
    
    @Override
    public void run() {
        for(int i = 0; i < NTHREADS; i++) {
            pft[i].start();
        }
        
        while (anyThreadAlive()) {
            try {
                Thread.sleep(TMILISECONDS);
                requestPauseAndWait();
                displayPrimeCount();
                waitForEnter();
                resumeAllThreads();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        
        System.out.println("Todos los hilos finalizaron. Total de primos: " + primeCount);
    }
    
    private void requestPauseAndWait() throws InterruptedException {
        synchronized (monitor) {
            currentState = State.PAUSED;
            while (threadsPaused < countActiveThreads()) {
                monitor.wait();
            }
        }
    }

    private void resumeAllThreads() {
        synchronized (monitor) {
            threadsPaused = 0;
            currentState = State.RUNNING;
            monitor.notifyAll();
        }
    }
    
    public void reportPrime() {
        synchronized (monitor) {
            primeCount++;
        }
    }

    public void reportThreadPaused() {
        synchronized (monitor) {
            threadsPaused++;
            monitor.notifyAll(); 
        }
    }

    public boolean shouldPause() {
        synchronized (monitor) {
            return currentState == State.PAUSED;
        }
    }

    public Object getMonitor() {
        return monitor;
    }
    
    private void displayPrimeCount() {
        System.out.println("=== PAUSA ===");
        System.out.println("Números primos encontrados hasta ahora: " + primeCount);
    }
    
    private void waitForEnter() {
        System.out.println("Presiona ENTER para continuar...");
        try {
            new BufferedReader(new InputStreamReader(System.in)).readLine();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
    
    private boolean anyThreadAlive() {
        for (PrimeFinderThread thread : pft) {
            if (thread.isAlive()) {
                return true;
            }
        }
        return false;
    }
    
    private int countActiveThreads() {
        int count = 0;
        for (PrimeFinderThread thread : pft) {
            if (thread.isAlive()) {
                count++;
            }
        }
        return count;
    }
}
