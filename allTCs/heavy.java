
class Node {}

class A {
    Node af;
    A ag;
    A() {
        af = new Node();
    }

    int m1() {
        return 1;
    }

    int m2() {
        return 2;
    }

    int getSum() {
        return m1() + m2();
    }
}

class B extends A {
    Node bf;
    A bg;
    int m1() {
        return 3;
    }

    int m2() {
        return 4;
    }

    int getSum() {
        return m1() + m2();
    }
}

class Circle {
    double r;

    Circle(double r) {
        this.r = r;
    }

    public double getArea() {
        return (3.14 * r * r);
    }

    public double getCircumference() {
        return (2 * 3.14 * r);
    }
}

class Test {
    static A g;
    public static void main(String[] args) {
        Test t = new Test();
        System.out.println(t.heavySRs());
        System.out.println(t.heavyVIs());
    }

    Test() {}

    public double heavySRs() {
        int numIter = 10000000;
        double total = 0;
        while (numIter-- > 0) {
            Circle c = new Circle(0.5);
            total += c.getArea();
            total += c.getCircumference();
        }
        return total;
    }

    public int heavyVIs() {
        int numIter = 10000000;
        int total = 0;
        A a1 = new A();
        A a2 = new B();
        while (numIter-- > 0) {
            total += a1.getSum();
            total += a2.getSum();
        }
        return total;
    }
}