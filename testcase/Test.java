

class A {
    A f;
    void m1() {
        System.out.println("A:m1");
    }
}

class B extends A {
    A g;
    void m1() {
        System.out.println("B:m1");
    }
}

class Test {
    static A g;
    public static void main(String[] args) {
        Test t = new Test();
        A a = new A();
        a.f = new A();
        g = a.f;
        B b = new B();
        a = b;
        System.out.println(t.m1(5, 6));
    }

    Test() {}

    public int m1(int x, int y) {
        int z = x + y + m3(x);
        return z;
    }

    public int m3(int z) {
        int x = 0;
        while (x < 10) {
            x += z;
        }
        return x;
    }
}